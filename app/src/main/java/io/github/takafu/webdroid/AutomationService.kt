package io.github.takafu.webdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class AutomationService : Service() {

    companion object {
        private const val TAG = "AutomationService"
        private const val PORT = 8765
        private const val ACTION_STOP_SERVICES = "io.github.takafu.webdroid.STOP_SERVICES"
        private const val MAX_CONSOLE_LOGS = 200
        private const val MAX_ACTION_TRAIL = 100
        private var server: AutomationServer? = null
        private val gson = Gson()

        // Console log ring buffer
        private val consoleLogs = CopyOnWriteArrayList<BrowserConsoleLog>()
        private val logLock = Any()
        @Volatile private var lastLoggedMessage: String? = null
        @Volatile private var lastLoggedTimestamp: Long = 0L

        // Action trail (newest last)
        private val actionTrail = CopyOnWriteArrayList<ActionTrailEntry>()

        // Element indexing state
        @Volatile private var lastDomIndex: DomIndexResult? = null

        data class BrowserConsoleLog(
            val level: String,
            val message: String,
            val source: String,
            val line: Int,
            val url: String = "",
            val timestamp: Long = System.currentTimeMillis(),
        )

        data class ActionTrailEntry(
            val action: String,
            val detail: String,
            val ok: Boolean = true,
            val timestamp: Long = System.currentTimeMillis(),
        )

        data class IndexedElement(
            val id: Int,
            val tag: String,
            val type: String? = null,
            val name: String? = null,
            val text: String? = null,
            val placeholder: String? = null,
            val ariaLabel: String? = null,
            val role: String? = null,
            val href: String? = null,
            val isVisible: Boolean = true,
            val isClickable: Boolean = true,
            val inViewport: Boolean = true,
            val disabled: Boolean = false,
        )

        data class DomIndexResult(
            val url: String,
            val title: String,
            val elements: List<IndexedElement>,
            val textSummary: String,
            val scrollY: Int,
            val timestamp: Long = System.currentTimeMillis(),
        )

        fun addConsoleLog(level: String, message: String, source: String, line: Int, url: String) {
            synchronized(logLock) {
                val signature = "$level:$message:$source:$line"
                val now = System.currentTimeMillis()
                if (signature == lastLoggedMessage && (now - lastLoggedTimestamp) < 150L) return
                lastLoggedMessage = signature
                lastLoggedTimestamp = now
                consoleLogs.add(BrowserConsoleLog(level, message, source, line, url))
                while (consoleLogs.size > MAX_CONSOLE_LOGS) consoleLogs.removeAt(0)
            }
        }

        fun getConsoleLogs(): List<BrowserConsoleLog> = consoleLogs.toList()

        fun clearConsoleLogs() = consoleLogs.clear()

        fun addActionTrail(action: String, detail: String, ok: Boolean = true) {
            actionTrail.add(ActionTrailEntry(action, detail, ok))
            while (actionTrail.size > MAX_ACTION_TRAIL) actionTrail.removeAt(0)
        }

        fun getActionTrail(): List<ActionTrailEntry> = actionTrail.toList()

        fun clearActionTrail() = actionTrail.clear()

        fun setDomIndex(result: DomIndexResult?) {
            lastDomIndex = result
        }

        fun getDomIndex(): DomIndexResult? = lastDomIndex

        // Event callbacks
        fun onPageEvent(event: String, data: String) {
            Log.d(TAG, "Page event: $event - $data")
        }

        fun onConsoleMessage(message: String) {
            Log.d(TAG, "Console: $message")
        }

        fun onProgressChanged(progress: Int) {
            // Progress update
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, createNotification())
        }

        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICES) {
            stopService(Intent(this, FloatingBubbleService::class.java))
            stopSelf()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "browser_automation",
                "Browser Automation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP server for browser automation"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AutomationService::class.java).apply {
            action = ACTION_STOP_SERVICES
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "browser_automation")
                .setContentTitle("Browser Automation")
                .setContentText("HTTP Server running on port $PORT")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setDeleteIntent(stopPendingIntent)  // swipe = stop services
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Browser Automation")
                .setContentText("HTTP Server running on port $PORT")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setDeleteIntent(stopPendingIntent)  // swipe = stop services
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    private fun startServer() {
        try {
            server = AutomationServer()
            server?.start()
            Log.i(TAG, "HTTP Server started on port $PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    inner class AutomationServer : NanoHTTPD(PORT) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            Log.d(TAG, "Request: $method $uri")

            return when {
                uri == "/navigate" && method == Method.POST -> handleNavigate(session)
                uri == "/execute" && method == Method.POST -> handleExecute(session)
                uri == "/eval" && method == Method.POST -> handleEval(session)
                uri == "/screenshot" && method == Method.GET -> handleScreenshot()
                uri == "/back" && method == Method.POST -> handleBack()
                uri == "/forward" && method == Method.POST -> handleForward()
                uri == "/refresh" && method == Method.POST -> handleRefresh()
                uri == "/home" && method == Method.POST -> handleHome()
                uri == "/home/url" && method == Method.GET -> handleGetHomeUrl()
                uri == "/home/url" && method == Method.POST -> handleSetHomeUrl(session)
                uri == "/url" && method == Method.GET -> handleGetUrl()
                uri == "/title" && method == Method.GET -> handleGetTitle()
                uri == "/html" && method == Method.GET -> handleGetHtml()
                uri == "/ping" && method == Method.GET -> handlePing()
                uri == "/bubble/start" && method == Method.POST -> handleStartBubble()
                uri == "/bubble/stop" && method == Method.POST -> handleStopBubble()
                uri == "/bubble/minimize" && method == Method.POST -> handleMinimizeBubble()
                // UserAgent preset endpoints
                uri == "/ua" && method == Method.GET -> handleGetUa()
                uri == "/ua/default" && method == Method.POST -> handleSetUaDefault()
                uri == "/ua/google-login" && method == Method.POST -> handleSetUaGoogleLogin()
                uri == "/ua/custom" && method == Method.POST -> handleSetUaCustom(session)
                // New structured endpoints
                uri == "/index" && method == Method.POST -> handleIndex(session)
                uri == "/click" && method == Method.POST -> handleClick(session)
                uri == "/type" && method == Method.POST -> handleType(session)
                uri == "/scroll" && method == Method.POST -> handleScroll(session)
                uri == "/snapshot" && method == Method.GET -> handleSnapshot()
                uri == "/console" && method == Method.GET -> handleGetConsole(session)
                uri == "/console/clear" && method == Method.POST -> handleClearConsole()
                uri == "/trail" && method == Method.GET -> handleGetTrail()
                uri == "/trail/clear" && method == Method.POST -> handleClearTrail()
                uri == "/wait" && method == Method.POST -> handleWait(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error":"Not found"}"""
                )
            }
        }

        private fun handleNavigate(session: IHTTPSession): Response {
            val params = parseBody(session)
            val url = params["url"] as? String

            return if (url != null) {
                runOnMainThread {
                    BrowserActivity.webView?.loadUrl(url)
                }
                successResponse("Navigating to $url")
            } else {
                errorResponse("Missing 'url' parameter")
            }
        }

        private fun handleExecute(session: IHTTPSession): Response {
            val params = parseBody(session)
            val script = params["script"] as? String

            return if (script != null) {
                runOnMainThread {
                    BrowserActivity.webView?.evaluateJavascript(script, null)
                }
                successResponse("Script executed")
            } else {
                errorResponse("Missing 'script' parameter")
            }
        }

        private fun handleEval(session: IHTTPSession): Response {
            val params = parseBody(session)
            val script = params["script"] as? String

            return if (script != null) {
                var result: String? = null
                val lock = Object()

                runOnMainThread {
                    BrowserActivity.webView?.evaluateJavascript(script) { value ->
                        synchronized(lock) {
                            result = value
                            lock.notify()
                        }
                    }
                }

                // Wait for result
                synchronized(lock) {
                    try {
                        lock.wait(5000) // 5 second timeout
                    } catch (e: InterruptedException) {
                        return errorResponse("Timeout")
                    }
                }

                successResponse(result ?: "null", "result" to result)
            } else {
                errorResponse("Missing 'script' parameter")
            }
        }

        private fun handleScreenshot(): Response {
            var screenshot: String? = null
            val lock = Object()

            runOnMainThread {
                synchronized(lock) {
                    val webView = BrowserActivity.webView
                    if (webView != null) {
                        // Get WebView size (use default size if in bubble state)
                        var width = webView.width
                        var height = webView.height

                        // If in bubble state (size is 0), measure/layout with default size
                        if (width <= 0 || height <= 0) {
                            width = 1080  // Default width
                            height = 1920  // Default height

                            webView.measure(
                                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
                            )
                            webView.layout(0, 0, width, height)
                        }

                        val bitmap = android.graphics.Bitmap.createBitmap(
                            width,
                            height,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        webView.draw(canvas)

                        val outputStream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                        val bytes = outputStream.toByteArray()

                        screenshot = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                    lock.notify()
                }
            }

            synchronized(lock) {
                try {
                    lock.wait(5000)
                } catch (e: InterruptedException) {
                    return errorResponse("Timeout")
                }
            }

            return if (screenshot != null) {
                successResponse("Screenshot captured", "screenshot" to screenshot)
            } else {
                errorResponse("Failed to capture screenshot")
            }
        }

        private fun handleBack(): Response {
            runOnMainThread {
                BrowserActivity.webView?.goBack()
            }
            return successResponse("Navigated back")
        }

        private fun handleForward(): Response {
            runOnMainThread {
                BrowserActivity.webView?.goForward()
            }
            return successResponse("Navigated forward")
        }

        private fun handleRefresh(): Response {
            runOnMainThread {
                BrowserActivity.webView?.reload()
            }
            return successResponse("Page refreshed")
        }

        private fun handleHome(): Response {
            runOnMainThread {
                BrowserActivity.webView?.loadUrl(FloatingBubbleService.HOME_URL)
            }
            return successResponse("Navigated to home: ${FloatingBubbleService.HOME_URL}")
        }

        private fun handleGetHomeUrl(): Response {
            return successResponse(FloatingBubbleService.HOME_URL)
        }

        private fun handleSetHomeUrl(session: IHTTPSession): Response {
            val params = parseBody(session)
            val url = params["url"] as? String
            return if (url != null && url.isNotEmpty()) {
                FloatingBubbleService.setHomeUrl(url)
                runOnMainThread {
                    BrowserActivity.webView?.loadUrl(url)
                }
                successResponse("Home URL set to $url")
            } else {
                errorResponse("Missing 'url' parameter")
            }
        }

        private fun handleGetUrl(): Response {
            var url: String? = null
            val lock = Object()

            runOnMainThread {
                synchronized(lock) {
                    url = BrowserActivity.webView?.url
                    lock.notify()
                }
            }

            synchronized(lock) {
                try {
                    lock.wait(1000)
                } catch (e: InterruptedException) {
                    return errorResponse("Timeout")
                }
            }

            return successResponse(url ?: "about:blank", "url" to url)
        }

        private fun handleGetTitle(): Response {
            var title: String? = null
            val lock = Object()

            runOnMainThread {
                synchronized(lock) {
                    title = BrowserActivity.webView?.title
                    lock.notify()
                }
            }

            synchronized(lock) {
                try {
                    lock.wait(1000)
                } catch (e: InterruptedException) {
                    return errorResponse("Timeout")
                }
            }

            return successResponse(title ?: "", "title" to title)
        }

        private fun handleGetHtml(): Response {
            var html: String? = null
            val lock = Object()

            runOnMainThread {
                BrowserActivity.webView?.evaluateJavascript(
                    "document.documentElement.outerHTML"
                ) { value ->
                    synchronized(lock) {
                        html = value
                        lock.notify()
                    }
                }
            }

            synchronized(lock) {
                try {
                    lock.wait(5000)
                } catch (e: InterruptedException) {
                    return errorResponse("Timeout")
                }
            }

            return successResponse("HTML retrieved", "html" to html)
        }

        private fun handlePing(): Response {
            return successResponse("pong", "status" to "ok")
        }

        private fun handleStartBubble(): Response {
            val intent = Intent(applicationContext, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            return successResponse("Floating bubble started")
        }

        private fun handleStopBubble(): Response {
            val intent = Intent(applicationContext, FloatingBubbleService::class.java)
            applicationContext.stopService(intent)
            return successResponse("Floating bubble stopped")
        }

        private fun handleMinimizeBubble(): Response {
            FloatingBubbleService.minimizeWindow()
            return successResponse("Window minimized to bubble")
        }

        // ===================== NEW STRUCTURED ENDPOINTS =====================

        /**
         * POST /index — Run DOM indexing, return interactive elements + text
         * Returns: { success, url, title, elements: [...], textSummary, scrollY }
         */
        private fun handleIndex(session: IHTTPSession): Response {
            val params = parseBody(session)
            val force = (params["force"] as? Boolean) ?: false
            val forceStr = (params["force"] as? String)
            val forceBool = forceStr?.toBooleanStrictOrNull() ?: force

            return try {
                val indexResult = runDomIndex(forceBool) ?: return errorResponse("WebView not initialized")
                val (url, title, elements, textSummary, scrollY) = indexResult
                val elementsJson = gson.toJson(elements)
                val resultJson = buildString {
                    append("{\"success\":true,")
                    append("\"url\":").append(gson.toJson(url)).append(",")
                    append("\"title\":").append(gson.toJson(title)).append(",")
                    append("\"scrollY\":").append(scrollY).append(",")
                    append("\"elements\":").append(elementsJson).append(",")
                    append("\"textSummary\":").append(gson.toJson(textSummary))
                    append("}")
                }
                addActionTrail("index", "$url (${elements.size} elements)")
                newFixedLengthResponse(Response.Status.OK, "application/json", resultJson)
            } catch (e: Exception) {
                addActionTrail("index", "failed: ${e.message}", false)
                errorResponse("Failed to index DOM: ${e.message}")
            }
        }

        /**
         * POST /index helper — runs DOM indexing JS on main thread, returns result or null
         */
        private fun runDomIndex(force: Boolean): DomIndexResult? {
            val existing = if (!force) lastDomIndex else null
            if (existing != null && System.currentTimeMillis() - existing.timestamp < 3000) {
                return existing
            }

            val domIndexScript = """
(function() {
  try {
    let idCounter = 1;
    const elements = [];
    const interactiveSelectors = 'a, button, input, textarea, select, [role="button"], [role="link"], [role="checkbox"], [role="menuitem"], [role="tab"], [tabindex]:not([tabindex="-1"]), [onclick]';
    const nodes = document.querySelectorAll(interactiveSelectors);
    nodes.forEach(el => {
      const rect = el.getBoundingClientRect();
      const style = window.getComputedStyle(el);
      const isVisible = style.display !== 'none' &&
        style.visibility !== 'hidden' &&
        parseFloat(style.opacity || '1') > 0 &&
        (rect.width > 0 || rect.height > 0 || el.tagName === 'INPUT');
      if (isVisible) {
        const harnessId = idCounter++;
        el.setAttribute('data-harness-id', String(harnessId));
        const inViewport = rect.top < window.innerHeight && rect.bottom > 0 &&
          rect.left < window.innerWidth && rect.right > 0;
        const disabled = el.disabled === true || el.getAttribute('aria-disabled') === 'true';
        elements.push({
          id: harnessId,
          tag: el.tagName.toLowerCase(),
          type: el.getAttribute('type'),
          name: el.getAttribute('name'),
          text: (el.innerText || el.textContent || '').trim().substring(0, 100),
          placeholder: el.getAttribute('placeholder'),
          ariaLabel: el.getAttribute('aria-label'),
          role: el.getAttribute('role'),
          href: el.getAttribute('href'),
          isVisible: true,
          isClickable: true,
          inViewport: inViewport,
          disabled: disabled
        });
      }
    });
    const bodyText = (document.body ? (document.body.innerText || '') : '')
      .split('\n').map(l => l.trim()).filter(l => l.length > 0).slice(0, 40).join('\n');
    return JSON.stringify({
      url: window.location.href,
      title: document.title || '',
      elements: elements.slice(0, 120),
      textSummary: bodyText.substring(0, 2000),
      scrollY: Math.round(window.scrollY || 0)
    });
  } catch (e) {
    return JSON.stringify({
      url: window.location.href || '',
      title: document.title || '',
      elements: [],
      textSummary: '',
      error: String(e)
    });
  }
})();
""".trimIndent()

            var rawResult: String? = null
            val lock = Object()
            runOnMainThread {
                BrowserActivity.webView?.evaluateJavascript(domIndexScript) { value ->
                    synchronized(lock) {
                        rawResult = value
                        lock.notify()
                    }
                }
            }
            synchronized(lock) {
                try { lock.wait(3000) } catch (_: InterruptedException) { return null }
            }
            rawResult ?: return null

            val jsonStr = rawResult!!
                .removePrefix("\"")
                .removeSuffix("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

            return try {
                val obj = gson.fromJson(jsonStr, Map::class.java)
                val url = (obj["url"] as? String) ?: ""
                val title = (obj["title"] as? String) ?: ""
                val scrollY = ((obj["scrollY"] as? Number)?.toInt()) ?: 0
                val textSummary = (obj["textSummary"] as? String) ?: ""
                val rawElements = (obj["elements"] as? List<*>) ?: emptyList<Any>()
                val elements = rawElements.mapNotNull { item ->
                    val m = item as? Map<*, *> ?: return@mapNotNull null
                    IndexedElement(
                        id = ((m["id"] as? Number)?.toInt()) ?: return@mapNotNull null,
                        tag = (m["tag"] as? String) ?: "unknown",
                        type = m["type"] as? String,
                        name = m["name"] as? String,
                        text = m["text"] as? String,
                        placeholder = m["placeholder"] as? String,
                        ariaLabel = m["ariaLabel"] as? String,
                        role = m["role"] as? String,
                        href = m["href"] as? String,
                        isVisible = (m["isVisible"] as? Boolean) ?: true,
                        isClickable = (m["isClickable"] as? Boolean) ?: true,
                        inViewport = (m["inViewport"] as? Boolean) ?: true,
                        disabled = (m["disabled"] as? Boolean) ?: false,
                    )
                }
                DomIndexResult(url, title, elements, textSummary, scrollY)
            } catch (_: Exception) { null }
        }

        /**
         * POST /click — Click element by ID or CSS selector
         * Body: { id?: int, selector?: string }
         */
        private fun handleClick(session: IHTTPSession): Response {
            val params = parseBody(session)
            val id = (params["id"] as? Number)?.toInt()
            val selector = params["selector"] as? String

            if (id == null && selector.isNullOrBlank()) {
                return errorResponse("Provide either 'id' (integer) or 'selector' (string)")
            }

            val detail = selector?.take(80) ?: "#$id"
            return try {
                val targetSelector = selector ?: "[data-harness-id='$id']"
                val js = buildClickJs(targetSelector)
                var evalError: String? = null
                var evalResult: String? = null
                val lock = Object()
                runOnMainThread {
                    BrowserActivity.webView?.evaluateJavascript(js) { value ->
                        synchronized(lock) {
                            evalResult = value
                            lock.notify()
                        }
                    }
                }
                synchronized(lock) {
                    try { lock.wait(3000) } catch (_: InterruptedException) { evalError = "Timeout" }
                }
                if (evalError != null) {
                    addActionTrail("click", detail, false)
                    return errorResponse(evalError!!)
                }
                val warning = parseActionWarning(evalResult)
                val resultText = warning ?: "Element clicked"
                val state = getSnapshotState()
                addActionTrail("click", "$detail → $resultText")
                successResponse("$resultText\n\n$state", "warning" to warning)
            } catch (e: Exception) {
                addActionTrail("click", detail, false)
                errorResponse("Failed to click: ${e.message}")
            }
        }

        /**
         * POST /type — Type text into element
         * Body: { text: string, id?: int, selector?: string, clear_first?: boolean }
         */
        private fun handleType(session: IHTTPSession): Response {
            val params = parseBody(session)
            val text = params["text"] as? String ?: return errorResponse("Missing 'text' parameter")
            val id = (params["id"] as? Number)?.toInt()
            val selector = params["selector"] as? String
            val clearFirst = (params["clear_first"] as? Boolean) ?: false

            if (id == null && selector.isNullOrBlank()) {
                return errorResponse("Provide either 'id' (integer) or 'selector' (string)")
            }

            val detail = buildString {
                append(selector?.take(40) ?: "#$id")
                append(" \"").append(text.take(40)).append("\"")
            }

            return try {
                val targetSelector = selector ?: "[data-harness-id='$id']"
                val escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val clearJs = if (clearFirst) "field.value=''; " else ""
                val js = """
(function() {
  const field = document.querySelector("$targetSelector");
  if (!field) return JSON.stringify({ok:false, error:"Element not found"});
  field.focus();
  ${clearJs}field.value = "$escapedText";
  field.dispatchEvent(new Event('input', {bubbles: true}));
  field.dispatchEvent(new Event('change', {bubbles: true}));
  return JSON.stringify({ok: true});
})();
""".trimIndent()
                var evalError: String? = null
                var evalResult: String? = null
                val lock = Object()
                runOnMainThread {
                    BrowserActivity.webView?.evaluateJavascript(js) { value ->
                        synchronized(lock) {
                            evalResult = value
                            lock.notify()
                        }
                    }
                }
                synchronized(lock) {
                    try { lock.wait(3000) } catch (_: InterruptedException) { evalError = "Timeout" }
                }
                if (evalError != null) {
                    addActionTrail("type", detail, false)
                    return errorResponse(evalError!!)
                }
                val result = evalResult ?: "{\"ok\":true}"
                addActionTrail("type", detail, true)
                val state = getSnapshotState()
                successResponse("Typed: \"$text\"\n\n$state", "result" to result)
            } catch (e: Exception) {
                addActionTrail("type", detail, false)
                errorResponse("Failed to type: ${e.message}")
            }
        }

        /**
         * POST /scroll — Scroll the viewport
         * Body: { direction?: 'down'|'up'|'left'|'right', amount?: int }
         */
        private fun handleScroll(session: IHTTPSession): Response {
            val params = parseBody(session)
            val direction = (params["direction"] as? String) ?: "down"
            val amount = ((params["amount"] as? Number)?.toInt()) ?: 500

            return try {
                val js = buildScrollJs(direction, amount)
                runOnMainThread {
                    BrowserActivity.webView?.evaluateJavascript(js, null)
                }
                val state = getSnapshotState()
                addActionTrail("scroll", "$direction ${amount}px")
                successResponse("Scrolled $direction ${amount}px\n\n$state", "direction" to direction, "amount" to amount)
            } catch (e: Exception) {
                addActionTrail("scroll", "$direction ${amount}px", false)
                errorResponse("Failed to scroll: ${e.message}")
            }
        }

        /**
         * GET /snapshot — Quick read of current URL, title, and DOM without re-indexing
         */
        private fun handleSnapshot(): Response {
            return try {
                val state = getSnapshotState()
                successResponse(state)
            } catch (e: Exception) {
                errorResponse("Failed to get snapshot: ${e.message}")
            }
        }

        /**
         * GET /console — Get captured console logs
         * Query params: since?, level?
         */
        private fun handleGetConsole(session: IHTTPSession): Response {
            val params = session.parameters
            val since = params["since"]?.firstOrNull()?.toLongOrNull()
            val levelFilter = params["level"]?.firstOrNull()
            val logs = getConsoleLogs().filter { log ->
                val afterSince = since?.let { log.timestamp >= it } ?: true
                val matchesLevel = levelFilter?.let { log.level.equals(it, ignoreCase = true) } ?: true
                afterSince && matchesLevel
            }
            val logsJson = gson.toJson(logs)
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"logs":$logsJson,"count":${logs.size}}""")
        }

        /**
         * POST /console/clear — Clear the console log buffer
         */
        private fun handleClearConsole(): Response {
            clearConsoleLogs()
            addActionTrail("console/clear", "")
            return successResponse("Console logs cleared")
        }

        /**
         * GET /trail — Get action trail
         */
        private fun handleGetTrail(): Response {
            val trail = getActionTrail()
            val trailJson = gson.toJson(trail)
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"trail":$trailJson,"count":${trail.size}}""")
        }

        /**
         * POST /trail/clear — Clear the action trail
         */
        private fun handleClearTrail(): Response {
            clearActionTrail()
            return successResponse("Action trail cleared")
        }

        /**
         * POST /wait — Wait for a condition (selector, text, url_contains)
         * Body: { condition: 'selector'|'text'|'url_contains', value: string, timeout_ms?: int }
         */
        private fun handleWait(session: IHTTPSession): Response {
            val params = parseBody(session)
            val condition = params["condition"] as? String ?: return errorResponse("Missing 'condition' parameter")
            val value = params["value"] as? String ?: return errorResponse("Missing 'value' parameter")
            val timeoutMs = ((params["timeout_ms"] as? Number)?.toLong()) ?: 5000L
            val cappedTimeout = timeoutMs.coerceIn(250L, 30000L)

            return try {
                val predicate = when (condition.lowercase()) {
                    "selector" -> "(function(){ const el = document.querySelector(\"${value}\"); if (!el) return false; const r = el.getBoundingClientRect(); const s = window.getComputedStyle(el); return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0; })()"
                    "text" -> "((document.body ? (document.body.innerText || '') : '').indexOf(\"${value}\") !== -1)"
                    "url_contains" -> "window.location.href.indexOf(\"${value}\") !== -1"
                    else -> return errorResponse("Unknown condition '$condition'. Use: selector, text, url_contains")
                }

                val deadline = System.currentTimeMillis() + cappedTimeout
                while (System.currentTimeMillis() < deadline) {
                    var hit: Boolean? = false
                    var evalError: String? = null
                    val lock = Object()
                    val js = "(function(){ try { return JSON.stringify({ok:true, hit:!!($predicate)}); } catch(e) { return JSON.stringify({ok:false, error:String(e)}); } })()"
                    runOnMainThread {
                        BrowserActivity.webView?.evaluateJavascript(js) { value ->
                            synchronized(lock) {
                                try {
                                    val decoded = value.removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
                                    val obj = gson.fromJson(decoded, Map::class.java)
                                    if (obj["ok"] == true) hit = (obj["hit"] as? Boolean) ?: false
                                    else evalError = obj["error"] as? String
                                } catch (_: Exception) { evalError = "Parse error" }
                                lock.notify()
                            }
                        }
                    }
                    synchronized(lock) {
                        try { lock.wait(2000) } catch (_: InterruptedException) { evalError = "Timeout" }
                    }
                    when {
                        evalError != null -> {
                            addActionTrail("wait", "$condition=$value", false)
                            return errorResponse("Wait check failed: $evalError")
                        }
                        hit == true -> {
                            addActionTrail("wait", "$condition=$value")
                            return successResponse("Condition met.\n\n${getSnapshotState()}")
                        }
                    }
                    Thread.sleep(250)
                }
                addActionTrail("wait", "$condition=$value", false)
                errorResponse("Timed out after ${cappedTimeout}ms waiting for $condition '$value'")
            } catch (e: Exception) {
                addActionTrail("wait", "$condition=$value", false)
                errorResponse("Wait failed: ${e.message}")
            }
        }

        // ===================== Helper Functions =====================

        private fun getSnapshotState(): String {
            var url = ""
            var title = ""
            var scrollY = 0
            var error: String? = null
            val lock = Object()
            runOnMainThread {
                try {
                    val wv = BrowserActivity.webView ?: run { error = "WebView not initialized"; lock.notify(); return@runOnMainThread }
                    url = wv.url ?: ""
                    title = wv.title ?: ""
                    val snapshotJs = """
(function() {
  try {
    return JSON.stringify({
      scrollY: Math.round(window.scrollY || 0),
      title: String(document.title || ''),
      url: String(window.location.href || '')
    });
  } catch(e) { return JSON.stringify({scrollY:0,title:'',url:''}); }
})();
""".trimIndent()
                    wv.evaluateJavascript(snapshotJs) { value ->
                        synchronized(lock) {
                            try {
                                val decoded = value.removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
                                val obj = gson.fromJson(decoded, Map::class.java)
                                scrollY = ((obj["scrollY"] as? Number)?.toInt()) ?: 0
                                title = (obj["title"] as? String) ?: title
                                url = (obj["url"] as? String) ?: url
                            } catch (_: Exception) { }
                            lock.notify()
                        }
                    }
                } catch (e: Exception) {
                    error = e.message
                    lock.notify()
                }
            }
            synchronized(lock) {
                try { lock.wait(2000) } catch (_: InterruptedException) { }
            }

            val sb = StringBuilder()
            sb.append("URL: ").append(url.ifBlank { "(blank)" }).append("\n")
            sb.append("Title: ").append(title.ifBlank { "(no title)" }).append("\n")
            sb.append("Scroll: y=").append(scrollY).append("\n")
            error?.let { sb.append("Error: ").append(it).append("\n") }
            return sb.toString().trimEnd()
        }

        private fun buildClickJs(selector: String): String {
            val selJson = gson.toJson(selector)
            return """
(function() {
  const el = document.querySelector($selJson);
  if (!el) return JSON.stringify({ok:false, error:"Element not found"});
  el.scrollIntoView({block: 'center'});
  el.click();
  return JSON.stringify({ok:true, tag: el.tagName.toLowerCase(), text: (el.innerText || el.textContent || '').trim().substring(0, 100)});
})();
""".trimIndent()
        }

        private fun buildScrollJs(direction: String, amount: Int): String {
            val (dx, dy) = when (direction.lowercase()) {
                "up" -> 0 to -amount
                "down" -> 0 to amount
                "left" -> -amount to 0
                "right" -> amount to 0
                else -> 0 to amount
            }
            return "window.scrollBy({ top: $dy, left: $dx, behavior: 'instant' });"
        }

        private fun parseActionWarning(raw: String?): String? {
            if (raw == null) return null
            return try {
                val decoded = raw.removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
                val obj = gson.fromJson(decoded, Map::class.java)
                if (obj["ok"] == true) {
                    (obj["warning"] as? String)?.takeIf { it.isNotBlank() }
                } else {
                    (obj["error"] as? String) ?: "Unknown action failure"
                }
            } catch (_: Exception) { null }
        }

        // UserAgent preset handlers
        private fun handleGetUa(): Response {
            val mode = FloatingBubbleService.getUaMode()
            val ua = FloatingBubbleService.getCurrentUa()
            return successResponse("Current UA mode: $mode",
                "mode" to mode,
                "userAgent" to ua
            )
        }

        private fun handleSetUaDefault(): Response {
            FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_DEFAULT)
            return successResponse("UA set to default (Desktop Chrome)",
                "mode" to FloatingBubbleService.UA_MODE_DEFAULT,
                "userAgent" to FloatingBubbleService.getCurrentUa()
            )
        }

        private fun handleSetUaGoogleLogin(): Response {
            // Google login mode: Android Chrome UA + window.chrome injection
            // Bypasses Google's WebView detection
            FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_GOOGLE_LOGIN)
            return successResponse("UA set to google-login (Android Chrome + WebView bypass)",
                "mode" to FloatingBubbleService.UA_MODE_GOOGLE_LOGIN,
                "userAgent" to FloatingBubbleService.getCurrentUa()
            )
        }

        private fun handleSetUaCustom(session: IHTTPSession): Response {
            val params = parseBody(session)
            val ua = params["ua"] as? String
            return if (ua != null) {
                FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_CUSTOM, ua)
                successResponse("UA set to custom",
                    "mode" to FloatingBubbleService.UA_MODE_CUSTOM,
                    "userAgent" to ua
                )
            } else {
                errorResponse("Missing 'ua' parameter")
            }
        }

        private fun parseBody(session: IHTTPSession): Map<String, Any> {
            val files = mutableMapOf<String, String>()
            try {
                session.parseBody(files)
                val postData = files["postData"] ?: return emptyMap()
                return gson.fromJson(postData, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse body", e)
                return emptyMap()
            }
        }

        private fun successResponse(message: String, vararg data: Pair<String, Any?>): Response {
            val result = mutableMapOf<String, Any?>(
                "success" to true,
                "message" to message
            )
            data.forEach { (key, value) ->
                result[key] = value
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(result)
            )
        }

        private fun errorResponse(message: String): Response {
            val result = mapOf(
                "success" to false,
                "error" to message
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                gson.toJson(result)
            )
        }

        private fun runOnMainThread(action: () -> Unit) {
            Handler(Looper.getMainLooper()).post(action)
        }
    }
}
