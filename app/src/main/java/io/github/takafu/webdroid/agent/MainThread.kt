package io.github.takafu.webdroid.agent

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Main-thread bridge for the NanoHTTPD worker threads.
 *
 * Every WebView call must happen on the UI thread, but HTTP requests arrive on
 * NanoHTTPD's own pool threads and must block until the answer is known. The
 * original webdroid code used `Object.wait()`/`notify()`, which loses the
 * signal whenever the callback fires before the waiter reaches `wait()` — the
 * request then blocks for its full timeout and often returns null. A
 * CountDownLatch cannot drop a signal that arrived early.
 */
internal object MainThread {

    private val handler = Handler(Looper.getMainLooper())

    /** True when the caller is already on the UI thread. */
    val isMain: Boolean
        get() = Looper.myLooper() == Looper.getMainLooper()

    /** Runs [block] on the UI thread and waits for its result. */
    fun <T> await(timeoutMs: Long, fallback: T, block: () -> T): T {
        if (isMain) return block()
        val latch = CountDownLatch(1)
        var value = fallback
        handler.post {
            try {
                value = block()
            } catch (t: Throwable) {
                value = fallback
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) value else fallback
    }

    /** Posts fire-and-forget work to the UI thread. */
    fun post(block: () -> Unit) {
        if (isMain) block() else handler.post(block)
    }

    /**
     * Evaluates [script] and waits for the WebView's async callback.
     *
     * `evaluateJavascript` hands the return value to a callback on the UI
     * thread, so this is the async twin of [await].
     */
    fun eval(webView: WebView, script: String, timeoutMs: Long): String? {
        if (webView == null) return null
        val latch = CountDownLatch(1)
        var result: String? = null
        post {
            try {
                webView.evaluateJavascript(script) { value ->
                    result = value
                    latch.countDown()
                }
            } catch (t: Throwable) {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    /** Runs [block] on the UI thread with no waiting. */
    fun run(block: () -> Unit) = post(block)

    /** Sleeps without pinning a thread on the UI looper. */
    fun sleep(ms: Long) {
        if (ms <= 0) return
        Thread.sleep(ms)
    }
}