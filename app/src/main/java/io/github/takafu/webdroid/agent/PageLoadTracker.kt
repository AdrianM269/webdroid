package io.github.takafu.webdroid.agent

/**
 * Navigation lifecycle of the WebView the agent drives, fed by the WebViewClient
 * callbacks in FloatingBubbleService.
 *
 * `WebView.url` goes provisional the moment a navigation STARTS, so a slow
 * endpoint looks like a finished load: the agent asks for the URL right after
 * navigating and gets the *previous* document back, and scraping silently
 * returns the wrong page. This tracker separates the two facts an action needs:
 *
 *  - a navigation BEGAN  ([didStart])
 *  - the document it produced has COMMITTED and finished loading ([isSettled])
 *
 * When the WebViewClient is not ours the page itself is asked instead, because
 * the in-page `location.href` changes at commit while `WebView.url` changes at
 * start.
 */
internal class PageLoadTracker {

    /** The page's own view of its document, read from inside the page. */
    data class DocumentProbe(val url: String?, val ready: Boolean)

    /** Everything the settle decision needs, so it is testable as pure logic. */
    data class Snapshot(
        val generation: Int,
        val finishedGeneration: Int,
        val loading: Boolean,
        val committedUrl: String?,
        val documentUrl: String? = null,
        val documentReady: Boolean = false,
        val error: String? = null,
    )

    @Volatile
    private var generation = 0

    @Volatile
    private var finishedGeneration = 0

    @Volatile
    private var loading = false

    @Volatile
    private var committedUrl: String? = null

    @Volatile
    private var startedUrl: String? = null

    @Volatile
    private var error: String? = null

    @Volatile
    private var download: String? = null

    /** Baseline an action captures before it triggers a navigation. */
    val currentGeneration: Int get() = generation

    val isLoading: Boolean get() = loading

    /** The URL the in-flight navigation was started for, from onPageStarted. */
    val currentStartedUrl: String? get() = startedUrl

    /** A main-frame navigation began: onPageStarted. */
    fun onStarted(url: String?) {
        generation++
        loading = true
        error = null
        startedUrl = url
        // A download report is deliberately NOT cleared here: a main-frame
        // navigation starts first and only then does the response turn out to
        // be an attachment. It is cleared when the next action targets the page.
    }

    /** Forgets the previous action's download report. */
    fun clearDownload() {
        download = null
    }

    /**
     * The main-frame document finished loading: onPageFinished.
     *
     * Only counted while a load is in flight, so the duplicate finishes WebView
     * emits for one navigation cannot each advance the marker.
     */
    fun onFinished(url: String?) {
        if (!loading) return
        finishedGeneration = generation
        loading = false
        if (!url.isNullOrBlank()) committedUrl = url
    }

    /** The main-frame navigation failed: onReceivedError(isForMainFrame). */
    fun onError(description: String?) {
        error = description
        loading = false
        // An error page is still a committed document; without this the caller
        // would wait out the whole finish timeout for a page that never loads.
        finishedGeneration = generation
    }

    /**
     * The main-frame response is a download rather than a document, so no page
     * will ever appear. Terminal for the wait.
     */
    fun onDownload(description: String) {
        download = description
        loading = false
        finishedGeneration = generation
    }

    /** True while an unexplained download is on record. */
    val hasDownload: Boolean get() = download != null

    /** Reads and clears the pending download report. */
    fun takeDownload(): String? {
        val reported = download
        download = null
        return reported
    }

    fun snapshot(probe: DocumentProbe? = null): Snapshot = Snapshot(
        generation = generation,
        finishedGeneration = finishedGeneration,
        loading = loading,
        committedUrl = committedUrl,
        documentUrl = probe?.url,
        documentReady = probe?.ready == true,
        error = error,
    )

    companion object {
        /** True when the action's navigation actually began. */
        fun didStart(snapshot: Snapshot, generationBefore: Int, urlBefore: String?): Boolean =
            snapshot.generation > generationBefore || documentChanged(snapshot, urlBefore)

        /**
         * True when the document the action triggered is on screen and done
         * loading. The in-page fallback requires a DIFFERENT document that
         * reports readyState complete: while a page is still loading the
         * previous document keeps reporting its own href, which is exactly the
         * stale state that would otherwise be published as a successful
         * navigation.
         */
        fun isSettled(snapshot: Snapshot, generationBefore: Int, urlBefore: String?): Boolean {
            if (snapshot.finishedGeneration > generationBefore) return true
            if (snapshot.documentUrl == null || !snapshot.documentReady) return false
            return urlBefore == null || documentChanged(snapshot, urlBefore)
        }

        private fun documentChanged(snapshot: Snapshot, urlBefore: String?): Boolean =
            urlBefore != null &&
                snapshot.documentUrl != null &&
                snapshot.documentUrl != urlBefore
    }
}