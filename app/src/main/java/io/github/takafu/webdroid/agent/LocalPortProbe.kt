package io.github.takafu.webdroid.agent

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fast, lightweight local TCP port probe for the in-app preview hub.
 *
 * The preview hub needs to answer "which localhost dev servers are actually
 * running right now" without the user typing ports. Probing is a bare TCP
 * connect, so a dead port costs one refused connection rather than an HTTP
 * timeout.
 */
object LocalPortProbe {

    /**
     * Port the workspace file server (`serve.sh`) binds.
     *
     * This is the only port a caller needs to *discover*, because it is the one
     * the user starts themselves. Anything else the agent is investigating is
     * navigated to explicitly, so a guessed list of dev-server ports adds
     * nothing but noise.
     */
    const val WORKSPACE_PORT = 8899

    /** Upper bound on a range scan, so a request can never hang. */
    const val MAX_SCAN_PORTS = 2000

    /** Check whether a specific port is actively listening on loopback. */
    fun isPortOpen(port: Int, timeoutMs: Int = 60): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Probes every port in parallel and returns the ones that are open.
     *
     * Sequential probing of 20 ports at a 60ms timeout each is over a second on
     * a phone; the pool keeps the whole scan well inside one animation frame
     * budget even on a slow device.
     */
    fun probe(ports: List<Int>, timeoutMs: Int = 60): List<Int> {
        val pool = Executors.newFixedThreadPool(minOf(ports.size, 12))
        return try {
            pool.invokeAll(
                ports.map { port -> java.util.concurrent.Callable { if (isPortOpen(port, timeoutMs)) port else null } }
            ).mapNotNull { it.get(5, TimeUnit.SECONDS) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    /** Extracts potential localhost port numbers from output text (dev server logs). */
    fun extractPortsFromText(text: String): List<Int> =
        Regex("""(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]):(\d{2,5})""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..65535 }
            .distinct()
            .toList()

    /** True when the URL is a loopback web address. */
    fun isLocalhostUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://localhost") || lower.startsWith("https://localhost") ||
            lower.startsWith("http://127.0.0.1") || lower.startsWith("https://127.0.0.1") ||
            lower.startsWith("localhost:") || lower.startsWith("127.0.0.1:")
    }

    /**
     * Extracts the port from a localhost URL or bare host:port string, falling
     * back to the scheme default (80/443). Null when no port can be determined.
     */
    fun portOfUrl(url: String): Int? {
        val trimmed = url.trim()
        try {
            val port = java.net.URI(trimmed).port
            if (port in 1..65535) return port
        } catch (_: Exception) {
        }
        extractPortsFromText(trimmed).firstOrNull()?.let { return it }
        if (!isLocalhostUrl(trimmed)) return null
        return if (trimmed.lowercase().startsWith("https")) 443 else 80
    }

    /**
     * Probes an explicit port range, skipping the well-known privileged ports
     * that no dev server binds. Bounded by the caller so a full range scan
     * cannot hold an HTTP request open for minutes.
     */
    fun probeRange(from: Int, to: Int, timeoutMs: Int = 40): List<Int> {
        val lo = from.coerceAtLeast(1024)
        val hi = to.coerceAtMost(65535)
        if (hi < lo) return emptyList()
        // An unbounded 1024-65535 sweep takes ~24s and would hold the HTTP
        // request open long enough to trip the agent's timeouts, so clamp to a
        // range that finishes in well under a second.
        val capped = if (hi - lo + 1 > MAX_SCAN_PORTS) (lo + MAX_SCAN_PORTS - 1) else hi
        return probe((lo..capped).toList(), timeoutMs)
    }

    /** Normalizes a port number, localhost URL, or bare host string to `http://localhost:<port>`. */
    fun normalizeLocalUrl(input: String): String {
        val trimmed = input.trim()
        val portOnly = trimmed.toIntOrNull()
        if (portOnly != null && portOnly in 1..65535) return "http://localhost:$portOnly"
        val lower = trimmed.lowercase()
        if (lower.startsWith("localhost:") || lower.startsWith("127.0.0.1:")) return "http://$trimmed"
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "http://$trimmed"
        return trimmed
    }
}