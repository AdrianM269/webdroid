package io.github.takafu.webdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import io.github.takafu.webdroid.agent.AgentBrowserController
import io.github.takafu.webdroid.agent.BrowserState
import io.github.takafu.webdroid.agent.LocalPortProbe
import io.github.takafu.webdroid.agent.MainThread
import io.github.takafu.webdroid.agent.WorkspacePathHandler
import java.io.IOException

/**
 * The HTTP control surface.
 *
 * Two families of endpoints coexist here:
 *
 *  - the original webdroid ones (/navigate, /eval, /screenshot, /html, the UA
 *    presets), kept compatible so existing Termux scripts keep working;
 *  - the agent-facing ones ported from AndroidHarness (/snapshot, /click, /type,
 *    /scroll, /wait_for, /logs, /diagnostics, /ports, /eruda), which return
 *    structured page state instead of fire-and-forget messages.
 *
 * Every handler runs on a NanoHTTPD worker thread and touches a WebView, so all
 * WebView work is marshalled to the UI thread by [AgentBrowserController].
 */
class AutomationService : Service() {

    companion object {
        private const val TAG = "AutomationService"
        const val PORT = 8765
        private const val ACTION_STOP_SERVICES = "io.github.takafu.webdroid.STOP_SERVICES"
        private var server: AutomationServer? = null
        private val gson = Gson()

        /**
         * The one agent controller, shared with the bubble service. Created
         * lazily because a Service's companion object may be touched before
         * onCreate.
         */
        @Volatile
        private var controllerRef: AgentBrowserController? = null

        fun controller(context: Context): AgentBrowserController =
            controllerRef ?: synchronized(this) {
                controllerRef ?: AgentBrowserController(context.applicationContext).also { controllerRef = it }
            }

        /** Direct access for the bubble service and UI. */
        fun peekController(): AgentBrowserController? = controllerRef

        /** Records a page-level console message from the WebChromeClient. */
        fun onConsoleMessage(message: android.webkit.ConsoleMessage?, pageUrl: String?) {
            Log.d(TAG, "Console: ${message?.message()}")
            controllerRef?.logs?.add(message, pageUrl)
        }

        /** Forwards page lifecycle events into the load tracker. */
        fun onPageEvent(event: String, data: String, url: String?) {
            Log.d(TAG, "Page event: $event - $data")
            val c = controllerRef ?: return
            when (event) {
                "page_started" -> c.tracker.onStarted(url)
                "page_finished" -> c.tracker.onFinished(url)
                "error" -> {
                    c.tracker.onError(data)
                    c.logs.addNavigationError(data, url)
                }
                "download" -> c.tracker.onDownload(data)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller(applicationContext)
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "HTTP server for browser automation" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AutomationService::class.java).apply {
            action = ACTION_STOP_SERVICES
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "browser_automation")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Browser Automation")
            .setContentText("Agent HTTP server running on port $PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setDeleteIntent(stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        runCatching { server?.stop() }
        server = null
    }

    inner class AutomationServer : NanoHTTPD(PORT) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            Log.d(TAG, "Request: $method $uri")
            val ctrl = controller(applicationContext)

            return try {
                when {
                    // ---- Original webdroid surface (kept compatible) ----
                    uri == "/navigate" && method == Method.POST -> handleNavigate(ctrl, session)
                    uri == "/execute" && method == Method.POST -> handleExecute(ctrl, session)
                    uri == "/eval" && method == Method.POST -> handleEval(ctrl, session)
                    uri == "/screenshot" && method == Method.GET -> handleScreenshot(ctrl)
                    uri == "/back" && method == Method.POST -> handleBack(ctrl)
                    uri == "/forward" && method == Method.POST -> handleForward(ctrl)
                    uri == "/refresh" && method == Method.POST -> handleRefresh(ctrl)
                    uri == "/url" && method == Method.GET -> handleGetUrl(ctrl)
                    uri == "/title" && method == Method.GET -> handleGetTitle(ctrl)
                    uri == "/html" && method == Method.GET -> handleGetHtml()
                    uri == "/ping" && method == Method.GET -> successResponse("pong", "status" to "ok")
                    uri == "/bubble/start" && method == Method.POST -> handleStartBubble()
                    uri == "/bubble/stop" && method == Method.POST -> handleStopBubble()
                    uri == "/bubble/minimize" && method == Method.POST -> handleMinimizeBubble()
                    uri == "/bubble/state" && method == Method.GET -> handleBubbleState()
                    uri == "/ua" && method == Method.GET -> handleGetUa()
                    uri == "/workspace" && method == Method.GET -> handleGetWorkspace()
                    uri == "/workspace" && method == Method.POST -> handleSetWorkspace(session)
                    uri == "/ua/default" && method == Method.POST -> handleSetUaDefault()
                    uri == "/ua/google-login" && method == Method.POST -> handleSetUaGoogleLogin()
                    uri == "/ua/custom" && method == Method.POST -> handleSetUaCustom(session)

                    // ---- Agent surface (ported from AndroidHarness) ----
                    uri == "/snapshot" -> handleSnapshot(ctrl)
                    uri == "/click" && method == Method.POST -> handleClick(ctrl, session)
                    uri == "/type" && method == Method.POST -> handleType(ctrl, session)
                    uri == "/scroll" && method == Method.POST -> handleScroll(ctrl, session)
                    uri == "/wait_for" && method == Method.POST -> handleWaitFor(ctrl, session)
                    uri == "/logs" -> handleLogs(ctrl, session)
                    uri == "/diagnostics" -> handleDiagnostics(ctrl)
                    uri == "/eruda" -> handleEruda(ctrl)
                    uri == "/ports" -> handlePorts(session)
                    uri == "/targets" -> handleTargets(ctrl)
                    uri == "/capabilities" -> handleCapabilities()
                    uri == "/health" -> handleHealth(ctrl)

                    else -> notFound()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Handler failed for $uri", t)
                errorResponse("Handler error: ${t.message ?: t.javaClass.simpleName}")
            }
        }

        // ---- Original handlers ----

        private fun handleNavigate(ctrl: AgentBrowserController, session: IHTTPSession): Response {
            val params = parseBody(session)
            val url = params["url"] as? String ?: return errorResponse("Missing 'url' parameter")
            return stateResponse(ctrl.navigate(url))
        }

        private fun handleExecute(ctrl: AgentBrowserController, session: IHTTPSession): Response {
            val params = parseBody(session)
            val script = params["script"] as? String ?: return errorResponse("Missing 'script' parameter")
            MainThread.post { BrowserActivity.webView?.evaluateJavascript(script, null) }
            return successResponse("Script executed")
        }

        private fun handleEval(ctrl: AgentBrowserController, session: IHTTPSession): Response {
            val params = parseBody(session)
            val script = params["script"] as? String ?: return errorResponse("Missing 'script' parameter")
            val outcome = ctrl.evalUser(script)
            return successResponse(
                if (outcome.ok) "Evaluated" else "JavaScript error",
                "result" to outcome.value,
                "error" to outcome.error,
            )
        }

        private fun handleScreenshot(ctrl: AgentBrowserController): Response {
            val shot = ctrl.screenshot()
                ?: return errorResponse("Failed to capture screenshot (WebView not ready)")
            return successResponse(
                "Screenshot captured",
                "screenshot" to shot.base64,
                "width" to shot.width,
                "height" to shot.height,
            )
        }

        private fun handleBack(ctrl: AgentBrowserController) = stateResponse(ctrl.back())
        private fun handleForward(ctrl: AgentBrowserController) = stateResponse(ctrl.forward())
        private fun handleRefresh(ctrl: AgentBrowserController) = stateResponse(ctrl.refresh())

        private fun handleGetUrl(ctrl: AgentBrowserController): Response {
            val (url, _) = ctrl.getUrl()
            return successResponse(url, "url" to url)
        }

        private fun handleGetTitle(ctrl: AgentBrowserController): Response {
            val (_, title) = ctrl.getUrl()
            return successResponse(title, "title" to title)
        }

        private fun handleGetHtml(): Response {
            val wv = BrowserActivity.webView ?: return errorResponse("WebView is not initialized")
            val html = MainThread.eval(wv, "document.documentElement.outerHTML", 5_000)
            return successResponse("HTML retrieved", "html" to html)
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
            applicationContext.stopService(Intent(applicationContext, FloatingBubbleService::class.java))
            return successResponse("Floating bubble stopped")
        }

        private fun handleMinimizeBubble(): Response {
            FloatingBubbleService.minimizeWindow()
            return successResponse("Window minimized to bubble")
        }

        private fun handleBubbleState(): Response = successResponse(
            "Bubble state",
            "expanded" to FloatingBubbleService.isExpanded(),
            "windowVisible" to FloatingBubbleService.isWindowVisible(),
            "running" to FloatingBubbleService.isRunning(),
        )

        private fun handleGetUa() = successResponse(
            "Current UA mode: ${FloatingBubbleService.getUaMode()}",
            "mode" to FloatingBubbleService.getUaMode(),
            "userAgent" to FloatingBubbleService.getCurrentUa(),
        )

        private fun handleGetWorkspace(): Response {
            val dir = FloatingBubbleService.currentWorkspaceDir()
            val listing: List<String>? =
                if (dir.isDirectory && dir.canRead()) dir.list()?.toList() ?: emptyList() else null
            return successResponse(
                "Workspace directory",
                "path" to dir.absolutePath,
                "exists" to dir.isDirectory,
                "readable" to (dir.isDirectory && dir.canRead()),
                "fileCount" to (listing?.size ?: 0),
                "files" to (listing?.sorted()?.take(50) ?: emptyList<String>()),
            )
        }

        private fun handleSetWorkspace(session: IHTTPSession): Response {
            // Match the other handlers: the JSON body arrives as `postData`,
            // so read the path from the parsed body, not session.parms.
            val body = parseBody(session)
            val raw = (body["path"] as? String)
                ?: (session.parms?.get("path"))
            if (raw.isNullOrBlank()) {
                return errorResponse("Missing 'path' parameter")
            }
            val dir = java.io.File(raw.trim())
            if (!dir.exists()) {
                // Under Android's sandbox another app's private directory is not
                // merely unreadable, it is invisible: exists() is false for a
                // path the user can plainly see in a file manager. Say so,
                // because "Not a directory" sends people hunting for a typo.
                val looksTermux = raw.contains("/com.termux/") || raw.contains("/data/data/")
                return errorResponse(
                    if (looksTermux) {
                        "Path does not exist as far as this app can see: $raw. " +
                            "Android 11+ hides other apps' private data entirely, so a " +
                            "path inside another app's /data/data can never be used here " +
                            "even though it exists. Serve the files over http://localhost " +
                            "and preview them by port instead."
                    } else {
                        "No such directory: $raw"
                    }
                )
            }
            if (!dir.isDirectory) {
                return errorResponse("Not a directory: $raw")
            }
            // The app usually lacks read access to other apps' private data
            // (Termux home included). Fail loudly now rather than serving
            // 'Not found' on every subsequent workspace request.
            if (!dir.canRead()) {
                return errorResponse(
                    "Directory exists but is not readable by this app: $raw. " +
                        "Android 11+ does not allow reading another app's private data; " +
                        "use shared storage (e.g. /sdcard/<dir>) or serve the files " +
                        "over http://localhost and preview them by port."
                )
            }
            FloatingBubbleService.setWorkspaceDir(dir)
            return successResponse(
                "Workspace directory set",
                "path" to FloatingBubbleService.currentWorkspaceDir().absolutePath,
            )
        }

        private fun handleSetUaDefault(): Response {
            FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_DEFAULT)
            return successResponse(
                "UA set to default (Desktop Chrome)",
                "mode" to FloatingBubbleService.getUaMode(),
                "userAgent" to FloatingBubbleService.getCurrentUa(),
            )
        }

        private fun handleSetUaGoogleLogin(): Response {
            FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_GOOGLE_LOGIN)
            return successResponse(
                "UA set to google-login (Android Chrome + WebView bypass)",
                "mode" to FloatingBubbleService.getUaMode(),
                "userAgent" to FloatingBubbleService.getCurrentUa(),
            )
        }

        private fun handleSetUaCustom(session: IHTTPSession): Response {
            val params = parseBody(session)
            val ua = params["ua"] as? String ?: return errorResponse("Missing 'ua' parameter")
            FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_CUSTOM, ua)
            return successResponse(
                "UA set to custom",
                "mode" to FloatingBubbleService.getUaMode(),
                "userAgent" to ua,
            )
        }

        // ---- Agent handlers ----

        private fun handleSnapshot(ctrl: AgentBrowserController) = stateResponse(ctrl.extractState())

        private fun handleClick(ctrl: AgentBrowserController, session: IHTTPSession): Response {
            val params = parseBody(session)
            val id = numberOf(params["id"])?.toInt()
            val selector = params["selector"] as? String
            if (id == null && selector.isNullOrBlank()) {
                return errorResponse("Provide either 'id' (integer) or 'selector' (string).")
            }
            return stateResponse(ctrl.click(elementId = id, selector = selector))
        }

        private fun handleType(ctrl: AgentBrowserController, session: IHTTPSession): Response {
            val params = parseBody(session)
            val text = params["text"] as? String ?: return errorResponse("Missing 'text' parameter")
            val id = numberOf(params["id"])?.toInt()
            val selector = params["selector"] as? String
            // Typing replaces the field by default. `clear_first` is the older
            // name for that same behaviour, so honour it as an alias.
            val append = params["append"] as? Boolean ?: false
            val clearFirst = params["clear_first"] as? Boolean ?: false
            val submit = params["submit"] as? Boolean ?: false
            if (id == null && selector.isNullOrBlank()) {
                return errorResponse("Provide either 'id' (integer) or 'selector' (string).")
            }
            return stateResponse(
                ctrl.type(
                    text,
                    elementId = id,
                    selector = selector,
                    append = append && !clearFirst,
                    submit = submit,
                )
            )
        }

        private fun handleScroll(ctrl: AgentBrowserController, session: IHTTPSession): Response {
            val params = parseBody(session)
            val direction = params["direction"] as? String ?: "down"
            val amount = numberOf(params["amount"])?.toInt() ?: 500
            return stateResponse(ctrl.scroll(direction, amount))
        }

        private fun handleWaitFor(ctrl: AgentBrowserController, session: IHTTPSession): Response {
            val params = parseBody(session)
            val condition = params["condition"] as? String ?: return errorResponse("Missing 'condition' parameter")
            val value = params["value"] as? String ?: return errorResponse("Missing 'value' parameter")
            val timeout = numberOf(params["timeout_ms"])?.toLong() ?: 5_000L
            return stateResponse(ctrl.waitFor(condition, value, timeout))
        }

        private fun handleLogs(ctrl: AgentBrowserController, session: IHTTPSession): Response {
            val params = parseBody(session)
            val level = params["level"] as? String
            val source = params["source"] as? String
            val clear = params["clear"] as? Boolean ?: false
            val entries = ctrl.logs.get(level, source, limit = 300)
            if (clear) ctrl.logs.clear()
            return successResponse(
                "${entries.size} console log(s)",
                "count" to entries.size,
                "logs" to entries.map {
                    mapOf(
                        "level" to it.level,
                        "message" to it.message,
                        "source" to it.source,
                        "line" to it.line,
                        "url" to it.url,
                    )
                },
            )
        }

        private fun handleDiagnostics(ctrl: AgentBrowserController) =
            successResponse("Diagnostics", "diagnostics" to ctrl.diagnostics())

        private fun handleEruda(ctrl: AgentBrowserController): Response {
            ctrl.toggleEruda()
            return successResponse("Eruda DevTools toggled in the current page")
        }

        private fun handlePorts(session: IHTTPSession): Response {
            val params = parseBody(session)
            val from = numberOf(params["from"])?.toInt()
            val to = numberOf(params["to"])?.toInt()
            if (from != null && to != null) {
                // Explicit range: the agent is diagnosing a specific span.
                val ports = LocalPortProbe.probeRange(from, to)
                return successResponse(
                    "${ports.size} open port(s) in $from-$to",
                    "ports" to ports,
                    "urls" to ports.map { "http://localhost:$it" },
                    "range" to "$from-$to",
                )
            }
            // Default: only the workspace file server, which is the one port a
            // caller cannot be expected to know. Everything else is navigated
            // to directly, so guessing dev-server ports finds nothing.
            val up = LocalPortProbe.isPortOpen(LocalPortProbe.WORKSPACE_PORT)
            return successResponse(
                if (up) "Workspace file server is up on ${LocalPortProbe.WORKSPACE_PORT}"
                else "Workspace file server is NOT running on ${LocalPortProbe.WORKSPACE_PORT}",
                "workspacePort" to LocalPortProbe.WORKSPACE_PORT,
                "running" to up,
                "ports" to (if (up) listOf(LocalPortProbe.WORKSPACE_PORT) else emptyList<Int>()),
                "urls" to (if (up) listOf("http://localhost:${LocalPortProbe.WORKSPACE_PORT}") else emptyList<String>()),
                "hint" to "Start it with: ./serve.sh   (pass from/to to scan a range instead)",
            )
        }

        private fun handleTargets(ctrl: AgentBrowserController): Response {
            val wsUp = LocalPortProbe.isPortOpen(LocalPortProbe.WORKSPACE_PORT)
            val (url, title) = ctrl.getUrl()
            return successResponse(
                "Available preview targets",
                "workspacePort" to LocalPortProbe.WORKSPACE_PORT,
                "workspaceServerUp" to wsUp,
                "workspaceHost" to WorkspacePathHandler.HOST,
                "currentUrl" to url,
                "currentTitle" to title,
                "examples" to listOf(
                    "http://localhost:3000",
                    "https://${WorkspacePathHandler.HOST}${WorkspacePathHandler.PATH_PREFIX}index.html",
                    "https://example.com",
                ),
            )
        }

        private fun handleCapabilities() = successResponse(
            "Capabilities",
            "capabilities" to listOf(
                "navigate", "snapshot", "click", "type", "scroll", "eval",
                "wait_for", "screenshot", "logs", "diagnostics", "eruda",
                "ports", "back", "forward", "refresh", "html", "workspace-files",
            ),
            "port" to PORT,
            "version" to "1.0-agent",
        )

        private fun handleHealth(ctrl: AgentBrowserController): Response {
            val (url, title) = ctrl.getUrl()
            return successResponse(
                "ok",
                "webviewReady" to (BrowserActivity.webView != null),
                "bubbleRunning" to FloatingBubbleService.isRunning(),
                "logCount" to ctrl.logs.size,
                "currentUrl" to url,
                "currentTitle" to title,
            )
        }

        // ---- Plumbing ----

        private fun numberOf(value: Any?): Number? = when (value) {
            is Number -> value
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }

        private fun parseBody(session: IHTTPSession): Map<String, Any> {
            return try {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                val postData = files["postData"] ?: return emptyMap()
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(postData, Map::class.java) as? Map<String, Any> ?: emptyMap()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse body", e)
                emptyMap()
            }
        }

        /** Serializes a full BrowserState as the agent-facing response shape. */
        private fun stateResponse(state: BrowserState): Response {
            val map = mutableMapOf<String, Any?>(
                "success" to (state.error == null),
                "url" to state.url,
                "title" to state.title,
                "ready" to state.ready,
                "scroll" to mapOf("x" to state.scrollX, "y" to state.scrollY),
                "viewport" to mapOf(
                    "height" to state.viewportHeight,
                    "documentHeight" to state.documentHeight,
                ),
                "elements" to state.interactiveElements.map {
                    mapOf(
                        "id" to it.id,
                        "tag" to it.tag,
                        "type" to it.type,
                        "name" to it.name,
                        "text" to it.text,
                        "placeholder" to it.placeholder,
                        "ariaLabel" to it.ariaLabel,
                        "role" to it.role,
                        "href" to it.href,
                        "value" to it.value,
                        "inViewport" to it.inViewport,
                        "disabled" to it.disabled,
                    )
                },
                "elementCount" to state.interactiveElements.size,
                "textSummary" to state.textSummary,
            )
            state.note?.let { map["note"] = it }
            state.warning?.let { map["warning"] = it }
            state.error?.let {
                map["error"] = it
                map["message"] = it
            }
            return jsonResponse(Response.Status.OK, map)
        }

        private fun successResponse(message: String, vararg data: Pair<String, Any?>): Response {
            val result = mutableMapOf<String, Any?>("success" to true, "message" to message)
            data.forEach { (key, value) -> result[key] = value }
            return jsonResponse(Response.Status.OK, result)
        }

        private fun errorResponse(message: String): Response = jsonResponse(
            Response.Status.BAD_REQUEST,
            mapOf("success" to false, "error" to message, "message" to message),
        )

        private fun notFound(): Response = jsonResponse(
            Response.Status.NOT_FOUND,
            mapOf("success" to false, "error" to "Not found"),
        )

        private fun jsonResponse(status: Response.Status, body: Map<String, Any?>): Response =
            newFixedLengthResponse(status, "application/json", gson.toJson(body))
    }
}