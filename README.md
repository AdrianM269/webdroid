# WebDroid

A WebView-based browser automation app controllable from Termux via HTTP API. Features a floating bubble interface for overlay browsing.

## Features

- **Floating Bubble UI** - Draggable overlay window with resize support
- **HTTP API** - Control browser from Termux on localhost:8765
- **Desktop Mode** - Desktop user agent for full-featured web apps
- **JavaScript Execution** - Run scripts and get results
- **Screenshot Capture** - Capture page as PNG
- **Password Manager Support** - Works with Bitwarden via auth dialog

## Fork Extra Features

- **Navigation Buttons** - Back, Forward, and Home buttons in the header. Long-press Home button to set a custom homepage URL
- **Eruda Inspired New API** - Inspired by Eruda-Android (console for mobile browsers). These enable interaction with web pages without writing raw JavaScript: index all clickable/typed elements, click/type by element ID, scroll the viewport, wait for conditions, and review console logs and action trails for debugging.

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
| GET | `/ua` | Get current user agent mode and string |
| POST | `/ua/default` | Set user agent to desktop Chrome |
| POST | `/ua/google-login` | Set user agent to Android Chrome (bypasses Google WebView detection) |
| POST | `/ua/custom` | Set custom user agent string |
| POST | `/index` | Index all interactive elements (returns IDs for click/type) |
| POST | `/click` | Click element by ID or CSS selector |
| POST | `/type` | Type text into element by ID or selector |
| POST | `/scroll` | Scroll viewport by direction and amount |
| GET | `/snapshot` | Quick read of URL, title, scroll position |
| GET | `/console` | Get captured console logs (query: `?level=ERROR&since=TIMESTAMP`) |
| POST | `/console/clear` | Clear console log buffer |
| GET | `/trail` | Get action trail with timestamps |
| POST | `/trail/clear` | Clear action trail |
| POST | `/wait` | Wait for condition (selector, text, or URL substring) |

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

### Eruda Examples

```bash
# Index all interactive elements (returns IDs for click/type)
curl -s -X POST http://localhost:8765/index -H "Content-Type: application/json" -d '{"force": true}'

# Click element by ID
curl -s -X POST http://localhost:8765/click -H "Content-Type: application/json" -d '{"id": 1}'

# Type text into input by ID
curl -s -X POST http://localhost:8765/type -H "Content-Type: application/json" -d '{"id": 3, "text": "hello"}'

# Scroll down 500px
curl -s -X POST http://localhost:8765/scroll -H "Content-Type: application/json" -d '{"direction": "down", "amount": 500}'

# Wait for element to appear
curl -s -X POST http://localhost:8765/wait -H "Content-Type: application/json" -d '{"condition": "selector", "value": ".loaded", "timeout_ms": 5000}'

# Check console for errors
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

- Android 8.0+
- Termux with ADB access
- Display over other apps permission
- Notifications permission

## Tech Stack

- Kotlin
- Gradle 9.2.0
- NanoHTTPD 2.3.1
- Gson 2.10.1
- AndroidX WebKit

## License

MIT License
