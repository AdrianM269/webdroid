# WebDroid

**A WebView browser-automation app for Android, driven from Termux over HTTP — built for AI agents that scrape.**

A floating bubble holds a real browser on your phone. From Termux you can navigate, inspect the DOM, click, type, scroll, run JavaScript, capture screenshots, and read console output — all over `localhost:8765`.

Fork of [takafu/webdroid](https://github.com/takafu/webdroid), extended with an agent-facing automation layer originally built in [AndroidHarness](https://github.com/Sanuu7/AndroidHarness).

---

## What this adds over the original

The upstream app could navigate and run JavaScript. This fork adds what an agent actually needs to scrape reliably:

| | |
|---|---|
| **DOM snapshots** | Indexed interactive elements with ids, so you can click "element 12" instead of guessing a CSS selector |
| **Sensible typing** | `/type` **replaces** the field by default — no more `logisticswarehouse` from appending to a pre-filled box |
| **Conditional waits** | `/wait_for` on a selector, text, or URL, with real timeouts instead of sleeping and hoping |
| **Console capture** | URL-tagged log buffer with `/logs` and `/diagnostics` |
| **Eruda injection** | Toggle full DevTools in the page, read its Elements tree programmatically |
| **Local file preview** | Serve your workspace over loopback and open it in the overlay |
| **Error honesty** | Unreachable hosts and `chrome-error://` pages are reported as failures, not as clean pages |
| **In-window navigation** | Back / forward / refresh buttons, plus a clickable address bar with search fallback |

## Quick start

```bash
# 1. build (needs JDK 17 + Android SDK in a proot container — see docs/SETUP.md)
./auto-dev.sh

# 2. install and launch
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set io.github.takafu.webdroid SYSTEM_ALERT_WINDOW allow
adb shell am start -n io.github.takafu.webdroid/.BrowserActivity

# 3. connect
adb reverse tcp:8765 tcp:8765
curl http://127.0.0.1:8765/health
```

## Scraping in four calls

```bash
# navigate and wait for the document to settle
curl -X POST localhost:8765/navigate -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/jobs"}'

# see what is interactable
curl -s localhost:8765/snapshot | jq '.elements[:5]'

# act on an indexed element
curl -X POST localhost:8765/click -H 'Content-Type: application/json' \
  -d '{"id":12}'

# pull structured data out
curl -X POST localhost:8765/eval -H 'Content-Type: application/json' \
  -d '{"script":"return JSON.stringify([...document.querySelectorAll(\"a\")].map(a=>a.href))"}'
```

## API

`GET` unless noted. All bodies are JSON. Full reference in [docs/API.md](docs/API.md).

**Navigation** — `POST /navigate` · `POST /back` · `POST /forward` · `POST /refresh`

**Page state** — `GET /snapshot` · `GET /url` · `GET /title` · `GET /html` · `GET /diagnostics`

**Interaction** — `POST /click` · `POST /type` · `POST /scroll` · `POST /wait_for` · `POST /eval` · `POST /execute`

**Output** — `GET /screenshot` (base64 PNG) · `GET /logs`

**Environment** — `POST /eruda` · `GET /ua` + `POST /ua/{default,google-login,custom}` · `GET /ports` · `GET /workspace` + `POST /workspace` · `GET /targets`

**Service** — `GET /health` · `GET /ping` · `GET /capabilities` · `POST /bubble/{start,stop,minimize}` · `GET /bubble/state`

<details>
<summary>Notes on a few endpoints</summary>

- **`/type`** replaces the field. Use `append:true` to build up a value, `submit:true` to fire a real Enter keypress plus `requestSubmit()`.
- **`/click`** and **`/type`** accept `{"id":N}` (from `/snapshot`) or `{"selector":"..."}`. **Re-snapshot after any re-render** — ids are reassigned on every read, and a stale id silently does nothing.
- **`/click` returns before navigation completes.** Confirm with a follow-up `/url` or `/wait_for` rather than trusting the click response.
- **`/wait_for`** takes `{"condition":"selector|text|url_contains","value":"...","timeout_ms":5000}`.
- **`/ports`** reports whether the workspace file server is up on 8899. Pass `from`/`to` to scan a range.

</details>

## Previewing local files

Android 11+ hides other apps' private data, so WebDroid **cannot** read `~/workspace` — no permission flag crosses that boundary. Serve it over loopback instead (loopback is shared by all apps):

```bash
./scripts/serve.sh          # http://localhost:8899, bound to 127.0.0.1
curl -X POST localhost:8765/navigate -H 'Content-Type: application/json' \
  -d '{"url":"http://localhost:8899/page.html"}'
```

## Architecture

```
Termux (curl)
    |
    | HTTP over adb reverse (localhost:8765)
    v
AutomationService (NanoHTTPD)
    |
    v
FloatingBubbleService  ──►  WebView (floating overlay)
    |
    v
agent/  ──  AgentBrowserController, ConsoleLogBuffer,
            PageLoadTracker, WorkspacePathHandler, MainThread
```

## Requirements

- Android 7.0+ (`minSdk 24`), tested on Android 16
- "Display over other apps" permission for the bubble
- Termux with `adb` for the control channel

## Tech stack

Kotlin · classic Android Views (no Compose) · NanoHTTPD · Gson · AndroidX WebKit

## Security note

The API on port 8765 has **no authentication**. Keep it behind `adb reverse` on a machine you trust; never expose it to a network you don't control.

## Documentation

- [docs/SETUP.md](docs/SETUP.md) — build environment
- [docs/API.md](docs/API.md) — endpoint reference
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) — common failures
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — architecture notes

## License

MIT — see [takafu/webdroid](https://github.com/takafu/webdroid).
