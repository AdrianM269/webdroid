package io.github.takafu.webdroid.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.webkit.WebView
import io.github.takafu.webdroid.BrowserActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream

/** One indexed interactive element from a DOM snapshot. */
data class BrowserElement(
    val id: Int,
    val tag: String,
    val type: String? = null,
    val name: String? = null,
    val text: String? = null,
    val placeholder: String? = null,
    val ariaLabel: String? = null,
    val role: String? = null,
    val href: String? = null,
    val value: String? = null,
    val inViewport: Boolean = false,
    val disabled: Boolean = false,
)

/** Everything one snapshot knows about the current page. */
data class BrowserState(
    val url: String = "",
    val title: String = "",
    val scrollY: Int = 0,
    val scrollX: Int = 0,
    val viewportHeight: Int = 0,
    val documentHeight: Int = 0,
    val interactiveElements: List<BrowserElement> = emptyList(),
    val textSummary: String = "",
    val error: String? = null,
    val warning: String? = null,
    val note: String? = null,
    val ready: Boolean = true,
)

/** Result of a sandboxed user-code evaluation. */
data class BrowserEvalOutcome(val ok: Boolean, val value: String?, val error: String? = null)

/** A captured PNG screenshot. */
data class BrowserScreenshot(val base64: String, val width: Int, val height: Int, val mime: String = "image/png")

/** Records the most recent agent action so the live-action bubble can show it. */
data class BrowserActionTrack(
    val action: String,
    val detail: String,
    val ok: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

/** The target for an element action: exactly one of id or selector. */
data class ElementTarget(val elementId: Int?, val selector: String?, val errorSubject: String = "Element") {
    companion object {
        /**
         * Picks the target when both an id and a selector were supplied: the
         * SELECTOR wins.
         *
         * An id only means anything for the page state it was indexed from, and
         * ids are reassigned on every DOM read. Deciding silently in favour of
         * the id turns a correct selector call into "Element with id 0 not
         * found" with no hint that the selector was ignored. A selector is
         * re-resolved in the page, so it is the one to trust.
         */
        fun resolve(elementId: Int?, selector: String?): ElementTarget? {
            val sel = selector?.takeIf { it.isNotBlank() }
            return when {
                sel != null -> ElementTarget(elementId = null, selector = sel, errorSubject = "Element matching '$sel'")
                elementId != null -> ElementTarget(elementId = elementId, selector = null, errorSubject = "Element with id $elementId")
                else -> null
            }
        }
    }
}

/**
 * The agent-facing browser driver: the harness-side of the merged app.
 *
 * Everything here is driven from HTTP handler threads but touches a WebView, so
 * each call marshals onto the UI thread and blocks on a latch. Actions that can
 * navigate (navigate/back/forward/refresh) additionally bracket themselves with
 * [PageLoadTracker] so the agent is never handed the previous page's DOM.
 */
class AgentBrowserController(private val context: Context) {

    companion object {
        private const val EVAL_TIMEOUT_MS = 10_000L
        private const val SNAPSHOT_TIMEOUT_MS = 8_000L
        private const val NAV_TIMEOUT_MS = 30_000L
        private const val SCREENSHOT_TIMEOUT_MS = 10_000L
        private const val PROBE_INTERVAL_MS = 120L
        private const val SCROLL_SETTLE_MS = 220L

        /** Attribute stamped onto indexed elements so ids resolve to real nodes. */
        const val ID_ATTR = "data-webdroid-id"

        private const val PROMISE_SENTINEL = "__WEBDROID_PENDING__"
        private val gson = Gson()

        // ---- Page scripts -------------------------------------------------

        /**
         * DOM indexing and interactive-element extraction.
         *
         * Visible interactive nodes are stamped with [ID_ATTR] and returned with
         * their metadata. Stamping is what makes ids resolvable later; ids are
         * reassigned on every read, so they are only valid for the state that
         * produced them.
         */
        val DOM_INDEXING_SCRIPT = """
            (function() {
                try {
                    var idCounter = 1;
                    var elements = [];
                    var interactiveSelectors = 'a, button, input, textarea, select, [role="button"], [role="link"], [role="checkbox"], [role="menuitem"], [role="tab"], [role="option"], [tabindex]:not([tabindex="-1"]), [onclick]';
                    var nodes = document.querySelectorAll(interactiveSelectors);
                    Array.prototype.forEach.call(nodes, function(el) {
                        var rect = el.getBoundingClientRect();
                        var style = window.getComputedStyle(el);
                        var isVisible = style.display !== 'none' &&
                                          style.visibility !== 'hidden' &&
                                          parseFloat(style.opacity || '1') > 0 &&
                                          (rect.width > 0 || rect.height > 0 || el.tagName === 'INPUT');
                        if (!isVisible) return;
                        var idx = idCounter++;
                        el.setAttribute('$ID_ATTR', String(idx));
                        var inViewport = rect.top < window.innerHeight && rect.bottom > 0 &&
                                         rect.left < window.innerWidth && rect.right > 0;
                        var disabled = el.disabled === true || el.getAttribute('aria-disabled') === 'true';
                        elements.push({
                            id: idx,
                            tag: el.tagName.toLowerCase(),
                            type: el.getAttribute('type'),
                            name: el.getAttribute('name'),
                            text: (el.innerText || el.textContent || '').trim().substring(0, 100),
                            placeholder: el.getAttribute('placeholder'),
                            ariaLabel: el.getAttribute('aria-label'),
                            role: el.getAttribute('role'),
                            href: el.getAttribute('href'),
                            value: (el.value !== undefined && el.type !== 'password') ? String(el.value).substring(0, 100) : null,
                            inViewport: inViewport,
                            disabled: disabled
                        });
                    });

                    var bodyText = (document.body ? (document.body.innerText || '') : '')
                        .split('\n').map(function(l) { return l.trim(); })
                        .filter(function(l) { return l.length > 0; })
                        .slice(0, 60).join('\n');

                    return JSON.stringify({
                        url: window.location.href,
                        title: document.title || '',
                        interactiveElements: elements.slice(0, 200),
                        textSummary: bodyText.substring(0, 4000),
                        scrollY: Math.round(window.scrollY || 0),
                        scrollX: Math.round(window.scrollX || 0),
                        viewportHeight: window.innerHeight || 0,
                        documentHeight: document.documentElement ? document.documentElement.scrollHeight : 0,
                        ready: document.readyState === 'complete'
                    });
                } catch (e) {
                    return JSON.stringify({ url: window.location.href || '', title: document.title || '', interactiveElements: [], textSummary: '', error: String(e) });
                }
            })();
        """.trimIndent()

        /** Reads the page's own view of its document. `location.href` moves at commit. */
        val DOCUMENT_PROBE_SCRIPT = """
            (function(){
                try {
                    return JSON.stringify({ url: window.location.href, ready: document.readyState === 'complete' });
                } catch (e) { return JSON.stringify({ url: null, ready: false }); }
            })();
        """.trimIndent()

        /** Parks a thenable's settlement so `evaluateJavascript` can return immediately. */
        private val STAGE_PROMISE_FN = """
            function __webdroidStage(v) {
                if (v !== undefined && v !== null && typeof v.then === 'function') {
                    window.__webdroidAsync = null;
                    window.__webdroidAsyncActive = true;
                    Promise.resolve(v).then(
                        function(pv) {
                            try {
                                window.__webdroidAsync = JSON.stringify({ ok: true, value: pv === undefined ? null : pv });
                            } catch (e) {
                                window.__webdroidAsync = JSON.stringify({ ok: false, error: String(e && e.message || e) });
                            }
                            window.__webdroidAsyncActive = false;
                        },
                        function(pe) {
                            window.__webdroidAsync = JSON.stringify({ ok: false, error: String(pe && pe.message || pe) });
                            window.__webdroidAsyncActive = false;
                        }
                    );
                    return true;
                }
                return false;
            }
        """.trimIndent()

        /** Poll script for a parked promise. */
        val STAGED_PROMISE_PROBE_SCRIPT = """
            (function(){
                try {
                    if (typeof window.__webdroidAsync === 'string') return String(window.__webdroidAsync);
                    if (window.__webdroidAsyncActive !== true) {
                        return JSON.stringify({ ok: false, error: "the page navigated away before the promise settled" });
                    }
                    return JSON.stringify({ ok: true, value: "$PROMISE_SENTINEL" });
                } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })();
        """.trimIndent()

        /**
         * Wraps user code so `await` and `return` work and thrown errors come
         * back as data instead of killing the callback.
         */
        fun buildEvalJs(code: String): String {
            val literal = gson.toJson(code)
            return """
                (function() {
                    $STAGE_PROMISE_FN
                    try {
                        var __v = eval($literal);
                        if (__webdroidStage(__v)) return JSON.stringify({ ok: true, value: "$PROMISE_SENTINEL" });
                        return JSON.stringify({ ok: true, value: __v === undefined ? null : __v });
                    } catch (e) {
                        if (e instanceof SyntaxError) {
                            try {
                                var __AF = (function() {
                                    try { return Object.getPrototypeOf(async function(){}).constructor; } catch(_) { return null; }
                                })() || Function;
                                var __f = (typeof __AF === 'function' && __AF !== Function)
                                    ? new __AF($literal)
                                    : new Function($literal);
                                var __r = __f.call(window);
                                if (__webdroidStage(__r)) return JSON.stringify({ ok: true, value: "$PROMISE_SENTINEL" });
                                return JSON.stringify({ ok: true, value: __r === undefined ? null : __r });
                            } catch (e2) {
                                return JSON.stringify({ ok: false, error: String(e2 && e2.message || e2) });
                            }
                        }
                        return JSON.stringify({ ok: false, error: String(e && e.message || e) });
                    }
                })();
            """.trimIndent()
        }

        /** Lookup prologue: by id only when no selector was supplied. */
        fun elementLookupJs(target: ElementTarget): String {
            val selector = target.selector
            if (selector != null) {
                val missing = gson.toJson("No element matches selector '$selector'. Re-run /snapshot for fresh ids.")
                return "var el = document.querySelector(${gson.toJson(selector)});\nif (!el) return { ok: false, error: $missing };"
            }
            val missing = gson.toJson(
                "Element with id ${target.elementId} not found. The page re-rendered; re-run /snapshot for fresh ids."
            )
            return "var el = document.querySelector('[$ID_ATTR=\"${target.elementId}\"]');\nif (!el) return { ok: false, error: $missing };"
        }

        /**
         * Appended to a successful action envelope: a disabled control swallows
         * the interaction by design, so the action used to come back as an
         * ordinary-looking success with nothing changed and the agent retried
         * blind. The action is still dispatched; only the reporting changes.
         */
        private const val DISABLED_WARNING_JS =
            ", warning: (el.disabled === true || el.getAttribute('aria-disabled') === 'true')" +
                " ? 'element is disabled, so this action most likely did nothing' : null"

        /** Page script for one click. Pure, so the contract is testable without a WebView. */
        fun buildClickJs(target: ElementTarget): String = """
            (function() {
                ${elementLookupJs(target)}
                el.scrollIntoView({ behavior: 'instant', block: 'center' });
                try { el.focus(); } catch (e) {}
                el.click();
                return { ok: true$DISABLED_WARNING_JS };
            })();
        """.trimIndent()

        /** Page script that types text into the target. Text is JSON-encoded so quotes cannot break it. */
        fun buildTypeJs(target: ElementTarget, text: String, clearFirst: Boolean): String {
            val encodedText = gson.toJson(text)
            val notTextField = gson.toJson("${target.errorSubject} is a <")
            val press = if (clearFirst) "el.value = '';" else ""
            return """
                (function() {
                    ${elementLookupJs(target)}
                    var tag = (el.tagName || '').toLowerCase();
                    if (tag !== 'input' && tag !== 'textarea' && tag !== 'select' && el.isContentEditable !== true) {
                        return { ok: false, error: $notTextField + tag + '>, not a text field.' };
                    }
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    try { el.focus(); } catch (e) {}
                    $press
                    if (el.isContentEditable === true) {
                        el.textContent = (el.textContent || '') + $encodedText;
                    } else {
                        el.value = (el.value || '') + $encodedText;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return { ok: true$DISABLED_WARNING_JS };
                })();
            """.trimIndent()
        }

        /**
         * Injects Eruda DevTools into the page.
         *
         * Toggles: first call loads and shows it, a second call hides it. Loaded
         * from a CDN on demand rather than bundled, so the app stays small and
         * pages that never ask for devtools pay nothing.
         */
        val ERUDA_INJECT_SCRIPT = """
            (function () {
                if (window.eruda) {
                    var el = document.querySelector('.eruda-container');
                    if (el && el.style.display !== 'none' && window.eruda._isInit) {
                        window.eruda.hide();
                    } else if (window.eruda._isInit) {
                        window.eruda.show();
                    } else {
                        window.eruda.init({ tool: ['console', 'elements', 'network', 'resources', 'info', 'snippets'] });
                        window.eruda.show();
                    }
                    return;
                }
                var script = document.createElement('script');
                script.src = 'https://cdn.jsdelivr.net/npm/eruda';
                script.onload = function () {
                    if (window.eruda) {
                        window.eruda.init({ tool: ['console', 'elements', 'network', 'resources', 'info', 'snippets'] });
                        window.eruda.show();
                    }
                };
                script.onerror = function() {
                    console.error('Failed to load Eruda DevTools from CDN. Check network connection.');
                };
                (document.head || document.documentElement).appendChild(script);
            })();
        """.trimIndent()
    }

    val logs = ConsoleLogBuffer()

    /** Navigation lifecycle, fed by the WebViewClient callbacks in the bubble service. */
    internal val tracker = PageLoadTracker()

    /** Last agent action, read by the live-action bubble. */
    @Volatile
    var lastAction: BrowserActionTrack? = null
        private set

    fun track(action: String, detail: String, ok: Boolean) {
        lastAction = BrowserActionTrack(action, detail, ok)
    }

    // ---- Core plumbing --------------------------------------------------

    private fun webView(): WebView? = BrowserActivity.webView

    /**
     * Reads the WebView's URL on the UI thread.
     *
     * `WebView.url` is a WebView method and throws if touched from any thread
     * other than the one it was created on. Every HTTP handler runs on a
     * NanoHTTPD worker, so an unguarded `webView.url` in a navigate or history
     * step fails the whole request with a threading exception.
     */
    private fun currentUrl(): String? {
        val wv = webView() ?: return null
        return MainThread.await(1_000, null) { wv.url }
    }

    /** Evaluates [script] and returns the raw callback payload. */
    private fun evalRaw(script: String, timeoutMs: Long = EVAL_TIMEOUT_MS): String? {
        val wv = webView() ?: return null
        return MainThread.eval(wv, script, timeoutMs)
    }

    /**
     * Unwraps the extra quoting layer `evaluateJavascript` adds when page code
     * itself returned a string.
     */
    fun decodeJsJson(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return runCatching { gson.fromJson(trimmed, String::class.java) }.getOrNull()
        }
        return trimmed.ifEmpty { null }
    }

    private fun parseJsonObject(raw: String?): JsonObject? {
        val decoded = decodeJsJson(raw) ?: return null
        return runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull()
    }

    // ---- State extraction -----------------------------------------------

    /** Extracts the full page state: elements, text, scroll, readiness. */
    fun extractState(webView: WebView? = null): BrowserState {
        val target = webView ?: webView() ?: return BrowserState(error = "WebView is not initialized.")
        val raw = MainThread.eval(target, DOM_INDEXING_SCRIPT, SNAPSHOT_TIMEOUT_MS)
        val obj = parseJsonObject(raw)
            ?: return BrowserState(url = currentUrl() ?: "", error = "Failed to parse page state (page may have navigated).")
        return obj.toBrowserState()
    }

    private fun JsonObject.toBrowserState(): BrowserState {
        fun str(key: String): String? =
            runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString }.getOrNull()

        val elements = runCatching {
            val arr = getAsJsonArray("interactiveElements") ?: return@runCatching emptyList<BrowserElement>()
            arr.mapNotNull { el ->
                runCatching {
                    val e = el.asJsonObject
                    BrowserElement(
                        id = e.get("id")?.asInt ?: return@mapNotNull null,
                        tag = e.get("tag")?.asString ?: "unknown",
                        type = e.strOrNull("type"),
                        name = e.strOrNull("name"),
                        text = e.strOrNull("text"),
                        placeholder = e.strOrNull("placeholder"),
                        ariaLabel = e.strOrNull("ariaLabel"),
                        role = e.strOrNull("role"),
                        href = e.strOrNull("href"),
                        value = e.strOrNull("value"),
                        inViewport = runCatching { e.get("inViewport")?.asBoolean }.getOrNull() ?: false,
                        disabled = runCatching { e.get("disabled")?.asBoolean }.getOrNull() ?: false,
                    )
                }.getOrNull()
            }
        }.getOrNull() ?: emptyList()

        return BrowserState(
            url = str("url") ?: "",
            title = str("title") ?: "",
            scrollY = runCatching { get("scrollY")?.asInt }.getOrNull() ?: 0,
            scrollX = runCatching { get("scrollX")?.asInt }.getOrNull() ?: 0,
            viewportHeight = runCatching { get("viewportHeight")?.asInt }.getOrNull() ?: 0,
            documentHeight = runCatching { get("documentHeight")?.asInt }.getOrNull() ?: 0,
            interactiveElements = elements,
            textSummary = str("textSummary") ?: "",
            error = str("error"),
        )
    }

    private fun JsonObject.strOrNull(key: String): String? =
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString }.getOrNull()

    private fun readDocumentProbe(): PageLoadTracker.DocumentProbe? {
        val obj = parseJsonObject(evalRaw(DOCUMENT_PROBE_SCRIPT, 2_000)) ?: return null
        return PageLoadTracker.DocumentProbe(
            url = runCatching { obj.get("url")?.asString }.getOrNull(),
            ready = runCatching { obj.get("ready")?.asBoolean }.getOrNull() ?: false,
        )
    }

    // ---- Navigation ------------------------------------------------------

    /**
     * Navigates and waits for the *new* document, not just for the call to
     * return. Returns a failure state rather than the previous page when the
     * target never produces one.
     */
    fun navigate(target: String): BrowserState {
        val url = normalizeTarget(target)
        track("navigate", url, ok = true)
        if (url == "about:blank") {
            MainThread.run { webView()?.loadUrl(url) }
            return BrowserState(url = url, note = "Blocked unusable URL.")
        }
        val generationBefore = tracker.currentGeneration
        val urlBefore = currentUrl()
        tracker.clearDownload()

        MainThread.run { webView()?.loadUrl(url) }

        val outcome = awaitSettle(generationBefore, urlBefore, NAV_TIMEOUT_MS)
        return applyOutcome(url, outcome)
    }

    /**
     * Expands agent-friendly shorthands: bare ports, localhost without scheme,
     * and workspace-relative files such as `index.html` or `docs/about.html`.
     */
    fun normalizeTarget(target: String): String {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return "about:blank"
        if (trimmed.toIntOrNull() in 1..65535) return LocalPortProbe.normalizeLocalUrl(trimmed)
        val lower = trimmed.lowercase()
        val looksLocal = LocalPortProbe.isLocalhostUrl(trimmed) || lower.startsWith("localhost:") || lower.startsWith("127.0.0.1:")
        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("://")
        if (looksLocal && !hasScheme) return LocalPortProbe.normalizeLocalUrl(trimmed)
        if (!hasScheme && looksLikeFilePath(trimmed)) {
            return WorkspacePathHandler.localFileUrl(trimmed)
        }
        if (!hasScheme && lower.startsWith("localhost")) return LocalPortProbe.normalizeLocalUrl(trimmed)
        if (!hasScheme) return "https://$trimmed"
        return trimmed
    }

    private fun looksLikeFilePath(target: String): Boolean =
        target.contains('/') && !target.startsWith("/") && target.endsWith(".html", true)

    /** Waits until the triggered navigation has committed and settled. */
    private fun awaitSettle(generationBefore: Int, urlBefore: String?, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        // A same-document or cached hit can finish before this thread wakes, so
        // the first probe happens immediately rather than after a sleep.
        while (System.currentTimeMillis() < deadline) {
            val snapshot = tracker.snapshot(readDocumentProbe())
            if (PageLoadTracker.isSettled(snapshot, generationBefore, urlBefore)) return null
            tracker.takeDownload()?.let { return it }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        val snapshot = tracker.snapshot(readDocumentProbe())
        if (PageLoadTracker.isSettled(snapshot, generationBefore, urlBefore)) return null
        return "Timed out after ${timeoutMs}ms waiting for the page to finish loading."
    }

    private fun applyOutcome(target: String, failure: String?): BrowserState {
        if (failure == null) {
            val state = extractState()
            return if (state.error != null) {
                state.copy(note = "Page reported an error: ${state.error}", ready = false)
            } else {
                state.copy(ready = true)
            }
        }
        val current = currentUrl() ?: ""
        return BrowserState(
            url = current,
            error = "Failed to load '$target': $failure",
            ready = false,
        )
    }

    fun back(): BrowserState = historyStep(back = true)
    fun forward(): BrowserState = historyStep(back = false)

    private fun historyStep(back: Boolean): BrowserState {
        val wv = webView() ?: return BrowserState(error = "WebView is not initialized.")
        // canGoBack/canGoForward are WebView methods and throw when called off
        // the UI thread, which is where every HTTP handler starts.
        val canStep = MainThread.await(1_000, false) { if (back) wv.canGoBack() else wv.canGoForward() }
        if (!canStep) {
            return extractState().copy(
                note = if (back) "Already at the oldest history entry." else "Already at the newest history entry."
            )
        }
        track(if (back) "back" else "forward", currentUrl() ?: "", ok = true)
        val generationBefore = tracker.currentGeneration
        val urlBefore = currentUrl()
        MainThread.run { if (back) wv.goBack() else wv.goForward() }
        val outcome = awaitSettle(generationBefore, urlBefore, NAV_TIMEOUT_MS)
        return applyOutcome(if (back) "back" else "forward", outcome)
    }

    fun refresh(): BrowserState {
        val wv = webView() ?: return BrowserState(error = "WebView is not initialized.")
        track("refresh", currentUrl() ?: "", ok = true)
        val generationBefore = tracker.currentGeneration
        val urlBefore = currentUrl()
        MainThread.run { wv.reload() }
        val outcome = awaitSettle(generationBefore, urlBefore, NAV_TIMEOUT_MS)
        return applyOutcome("refresh", outcome)
    }

    // ---- Element actions -------------------------------------------------

    fun click(elementId: Int? = null, selector: String? = null): BrowserState {
        val target = ElementTarget.resolve(elementId, selector)
            ?: return BrowserState(error = "Provide either 'id' (integer) or 'selector' (string).")
        track("click", target.selector ?: "id=${target.elementId}", ok = true)
        val obj = parseJsonObject(evalRaw(buildClickJs(target))) ?: return BrowserState(error = "No response from page.")
        return finishAction(obj, extractState())
    }

    fun type(text: String, elementId: Int? = null, selector: String? = null, clearFirst: Boolean = false): BrowserState {
        val target = ElementTarget.resolve(elementId, selector)
            ?: return BrowserState(error = "Provide either 'id' (integer) or 'selector' (string).")
        val preview = if (text.length > 24) text.take(24) + "…" else text
        val targetLabel = target.selector ?: "id=${target.elementId}"
        track("type", "$targetLabel <- '$preview'", ok = true)
        val obj = parseJsonObject(evalRaw(buildTypeJs(target, text, clearFirst)))
            ?: return BrowserState(error = "No response from page.")
        return finishAction(obj, extractState())
    }

    fun scroll(direction: String = "down", amountPx: Int = 500): BrowserState {
        val delta = amountPx.coerceIn(-100_000, 100_000)
        val script = when (direction.lowercase()) {
            "up" -> "window.scrollBy({ top: -$delta, behavior: 'instant' });"
            "down" -> "window.scrollBy({ top: $delta, behavior: 'instant' });"
            "left" -> "window.scrollBy({ left: -$delta, behavior: 'instant' });"
            "right" -> "window.scrollBy({ left: $delta, behavior: 'instant' });"
            "top" -> "window.scrollTo({ top: 0, behavior: 'instant' });"
            "bottom" -> "window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' });"
            else -> return BrowserState(error = "Unknown direction '$direction'. Use up, down, left, right, top, or bottom.")
        }
        track("scroll", "$direction $delta", ok = true)
        evalRaw(script)
        Thread.sleep(SCROLL_SETTLE_MS)
        return extractState()
    }

    /** Blocks until a condition holds on the page. */
    fun waitFor(condition: String, value: String, timeoutMs: Long = 5_000L): BrowserState {
        val capped = timeoutMs.coerceIn(0, 60_000L)
        val predicate = when (condition.lowercase()) {
            "selector" -> "!!document.querySelector(${gson.toJson(value)})"
            "text" -> "(document.body ? document.body.innerText : '').indexOf(${gson.toJson(value)}) !== -1"
            "url_contains" -> "window.location.href.indexOf(${gson.toJson(value)}) !== -1"
            else -> return BrowserState(error = "Unknown condition '$condition'. Use selector, text, or url_contains.")
        }
        track("wait_for", "$condition=$value", ok = true)
        val deadline = System.currentTimeMillis() + capped
        while (System.currentTimeMillis() < deadline) {
            val obj = parseJsonObject(
                evalRaw("(function(){ try { return JSON.stringify({ hit: !!($predicate) }); } catch(e) { return JSON.stringify({ hit: false }); } })()", 2_000)
            )
            if (runCatching { obj?.get("hit")?.asBoolean }.getOrNull() == true) {
                return extractState().copy(note = "Condition met.")
            }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        return BrowserState(
            url = currentUrl() ?: "",
            error = "Timed out after ${capped}ms waiting for $condition '$value'.",
        )
    }

    /** Runs an action script and folds its warning/error into the new state. */
    private fun finishAction(obj: JsonObject, state: BrowserState): BrowserState {
        val ok = runCatching { obj.get("ok")?.asBoolean }.getOrNull() ?: false
        val error = runCatching { obj.get("error")?.takeUnless { it.isJsonNull }?.asString }.getOrNull()
        val warning = runCatching { obj.get("warning")?.takeUnless { it.isJsonNull }?.asString }.getOrNull()
        track(if (ok) "action" else "action", warning ?: error ?: "done", ok)
        return if (ok) state.copy(warning = warning) else state.copy(error = error, warning = warning)
    }

    // ---- Eval ------------------------------------------------------------

    /**
     * Evaluates user code with `await`/`return` support, resolving promises by
     * parking them and polling. Each call is isolated: persist state through
     * localStorage, not globals.
     */
    fun evalUser(code: String, awaitPromiseMs: Long = 10_000L): BrowserEvalOutcome {
        track("eval", code.lines().firstOrNull()?.take(60) ?: "", ok = true)
        val raw = evalRaw(buildEvalJs(code), EVAL_TIMEOUT_MS)
        val obj = parseJsonObject(raw)
            ?: return BrowserEvalOutcome(false, null, "No result returned from evaluation.")
        val ok = runCatching { obj.get("ok")?.asBoolean }.getOrNull() ?: false
        if (!ok) {
            return BrowserEvalOutcome(false, null, obj.get("error")?.asString ?: "Unknown JavaScript error.")
        }
        val value = obj.get("value") ?: return BrowserEvalOutcome(true, null)
        if (!value.isJsonNull && value.asString == PROMISE_SENTINEL) {
            return awaitStagedPromise(awaitPromiseMs)
        }
        return BrowserEvalOutcome(true, renderValue(value))
    }

    private fun awaitStagedPromise(timeoutMs: Long): BrowserEvalOutcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val obj = parseJsonObject(evalRaw(STAGED_PROMISE_PROBE_SCRIPT, 2_000))
            if (obj != null) {
                val ok = runCatching { obj.get("ok")?.asBoolean }.getOrNull() ?: false
                val value = obj.get("value")
                if (ok) {
                    if (value == null || value.isJsonNull || value.asString != PROMISE_SENTINEL) {
                        return BrowserEvalOutcome(true, value?.let { renderValue(it) })
                    }
                } else {
                    return BrowserEvalOutcome(false, null, obj.get("error")?.asString ?: "Promise rejected.")
                }
            }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        return BrowserEvalOutcome(false, null, "Timed out after ${timeoutMs}ms waiting for the promise to settle.")
    }

    private fun renderValue(value: com.google.gson.JsonElement): String = when {
        value.isJsonNull -> "null"
        value.isJsonPrimitive -> value.asString
        else -> value.toString()
    }

    /** Cheap read of the current URL and title. */
    fun getUrl(): Pair<String, String> {
        val wv = webView() ?: return "" to ""
        return MainThread.await(1_000, "" to "") { (wv.url ?: "") to (wv.title ?: "") }
    }

    // ---- Screenshot ------------------------------------------------------

    /**
     * Captures the WebView as PNG. Works whether the bubble is expanded or
     * minimized: a collapsed WebView reports zero size, so it is measured and
     * laid out at a fallback size first.
     */
    fun screenshot(fallbackWidth: Int = 1080, fallbackHeight: Int = 1920): BrowserScreenshot? {
        val wv = webView() ?: return null
        return MainThread.await(SCREENSHOT_TIMEOUT_MS, null) {
            var width = wv.width
            var height = wv.height
            if (width <= 0 || height <= 0) {
                width = fallbackWidth
                height = fallbackHeight
                wv.measure(
                    View_MEASURE_EXACTLY(width),
                    View_MEASURE_EXACTLY(height),
                )
                wv.layout(0, 0, width, height)
            }
            runCatching {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                wv.draw(android.graphics.Canvas(bitmap))
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                BrowserScreenshot(
                    base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                    width = width,
                    height = height,
                )
            }.getOrNull()
        }
    }

    private fun View_MEASURE_EXACTLY(size: Int): Int =
        android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)

    // ---- DevTools --------------------------------------------------------

    /** Toggles Eruda DevTools in the current page. */
    fun toggleEruda(): Boolean {
        evalRaw(ERUDA_INJECT_SCRIPT, 3_000)
        return true
    }

    /** Summary of what is wrong on the page, for one-tap bug fixing. */
    fun diagnostics(): Map<String, Any?> {
        val state = extractState()
        return mapOf(
            "url" to state.url,
            "title" to state.title,
            "errorCount" to logs.errorCount,
            "lastError" to logs.lastError,
            "recentLogs" to logs.get(limit = 15).map {
                mapOf("level" to it.level, "message" to it.message, "source" to it.source, "line" to it.line, "url" to it.url)
            },
            "elementCount" to state.interactiveElements.size,
        )
    }
}