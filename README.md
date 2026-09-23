# WebDroid

A WebView-based browser automation app controllable from Termux via HTTP API. Features a floating bubble interface for overlay browsing.

## Features

- **Floating Bubble UI** - Draggable overlay window with resize support
- **HTTP API** - Control browser from Termux on localhost:8765
- **Desktop Mode** - Desktop user agent for full-featured web apps
- **JavaScript Execution** - Run scripts and get results
- **Screenshot Capture** - Capture page as PNG
- **Password Manager Support** - Works with Bitwarden via auth dialog
- **Navigation Buttons** - Back, Forward, and Home buttons in the header. Long-press Home button to set a custom homepage URL

## Fork Extra Features

Structured browser automation endpoints for agent-driven web interaction:

- **DOM Indexing** (`POST /index`) — Returns all interactive elements with IDs, tags, types, names, ARIA labels, hrefs, viewport position, and disabled state. The agent references elements by ID instead of raw CSS selectors.
- **Structured Click** (`POST /click`) — Click by element ID (from `/index`) or CSS selector. Returns new page state.
- **Structured Type** (`POST /type`) — Type text into input/textarea by ID or selector, with optional `clear_first`. Dispatches proper DOM events for reactive forms.
- **Viewport Scroll** (`POST /scroll`) — Scroll up/down/left/right by pixel amount.
- **Page Snapshot** (`GET /snapshot`) — Quick read of URL, title, scroll position without re-indexing.
- **Console Log Capture** (`GET /console`) — Retrieve captured console logs filtered by level (ERROR/WARN/DEBUG/LOG) and timestamp.
- **Action Trail** (`GET /trail`) — Timestamped log of every action (navigate, click, type, scroll) with pass/fail status for debugging agent interactions.
- **Wait Conditions** (`POST /wait`) — Block until a CSS selector appears, text is present, or URL contains a substring. Configurable timeout up to 30s.

**Benefits for AI coding agents:**
- No raw JS needed for common interactions (clicking, typing, scrolling)
- Indexed elements eliminate selector guesswork
- Console logs and action trail enable self-debugging
- Wait conditions replace blind `sleep` calls

## Quick Start

### 1. Build & Install

```bash
cd ~/webdroid
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Launch

```bash
adb shell am start -n io.github.takafu.webdroid/.BrowserActivity
```

### 3. Control from Termux

```bash
source ~/webdroid/client/browser.sh

browser_goto "https://example.com"
browser_title
browser_screenshot screenshot.png
browser_eval "document.querySelectorAll('a').length"
```

## API Endpoints

HTTP server runs on `localhost:8765`.

### Basic Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/ping` | Health check |
| GET | `/url` | Get current URL |
| GET | `/title` | Get page title |
| GET | `/html` | Get page HTML |
| GET | `/screenshot` | Get screenshot (Base64) |
| POST | `/navigate` | Navigate to URL |
| POST | `/back` | Go back |
| POST | `/forward` | Go forward |
| POST | `/refresh` | Reload page |
| POST | `/home` | Navigate to homepage |
| GET | `/home/url` | Get current homepage URL |
| POST | `/home/url` | Set homepage URL (body: `{"url":"..."}`) |
| POST | `/execute` | Run JavaScript |
| POST | `/eval` | Run JavaScript and return result |

### Structured Automation Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/index` | Index all interactive elements (returns IDs for click/type) |
| POST | `/click` | Click element by ID or CSS selector (body: `{"id": 1}` or `{"selector": "..."}`) |
| POST | `/type` | Type text into element (body: `{"id": 1, "text": "...", "clear_first": true}`) |
| POST | `/scroll` | Scroll viewport (body: `{"direction": "down", "amount": 500}`) |
| GET | `/snapshot` | Quick URL, title, scroll position |
| GET | `/console` | Get console logs (query: `?level=ERROR&since=TIMESTAMP`) |
| POST | `/console/clear` | Clear console log buffer |
| GET | `/trail` | Get action trail with timestamps |
| POST | `/trail/clear` | Clear action trail |
| POST | `/wait` | Wait for condition (body: `{"condition": "selector", "value": ".loaded", "timeout_ms": 5000}`) |

### Examples

```bash
# Navigate
curl -X POST http://localhost:8765/navigate \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'

# Get title
curl http://localhost:8765/title

# Execute JavaScript
curl -X POST http://localhost:8765/eval \
  -H "Content-Type: application/json" \
  -d '{"script":"document.title"}'
```

### Structured Automation Examples

```bash
# Index page elements (returns IDs for interactive elements)
curl -s -X POST http://localhost:8765/index -H "Content-Type: application/json" -d '{"force": true}'

# Click element by ID (from /index response)
curl -s -X POST http://localhost:8765/click -H "Content-Type: application/json" -d '{"id": 1}'

# Type text into input by ID
curl -s -X POST http://localhost:8765/type -H "Content-Type: application/json" -d '{"id": 3, "text": "hello world", "clear_first": true}'

# Scroll down
curl -s -X POST http://localhost:8765/scroll -H "Content-Type: application/json" -d '{"direction": "down", "amount": 500}'

# Wait for element to appear
curl -s -X POST http://localhost:8765/wait -H "Content-Type: application/json" -d '{"condition": "selector", "value": ".results", "timeout_ms": 10000}'

# Check console errors
curl -s "http://localhost:8765/console?level=ERROR"

# Review action trail
curl -s http://localhost:8765/trail
```

## Client Library

```bash
source ~/webdroid/client/browser.sh

browser_goto <url>          # Navigate to URL
browser_back                # Go back
browser_forward             # Go forward
browser_refresh             # Reload
browser_url                 # Get URL
browser_title               # Get title
browser_html                # Get HTML
browser_execute <script>    # Run JavaScript
browser_eval <script>       # Run JavaScript and return result
browser_screenshot [file]   # Take screenshot
browser_ping                # Health check
```

## Architecture

```
Termux (curl/browser.sh)
    |
    | HTTP (localhost:8765)
    v
AutomationService (NanoHTTPD)
    |
    v
FloatingBubbleService
    |
    v
WebView (floating overlay)
```

## Requirements

- Android 8.0+ (for overlay permissions)
- Termux with ADB access
- Display over other apps permission

## Tech Stack

- Kotlin
- Gradle 9.2.0
- NanoHTTPD 2.3.1
- Gson 2.10.1
- AndroidX WebKit

## License

MIT License
