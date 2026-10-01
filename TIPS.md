# Tips

## Page Loading

### Complex Sites (Slack, etc.)

Reload after initial load for better resource loading:
```bash
browser_goto "https://slack.com"
sleep 3
browser_refresh
sleep 5
```

### Wait for Element

```bash
while true; do
    exists=$(browser_eval "!!document.querySelector('#target')")
    if [ "$exists" = "true" ]; then
        break
    fi
    sleep 1
done
```

---

## Form Interaction

### Input Text (Standard)

```bash
browser_execute "
const input = document.querySelector('input[name=\"email\"]');
input.value = 'test@example.com';
input.dispatchEvent(new Event('input', { bubbles: true }));
"
```

### Input Text (React / controlled components)

Simple `.value =` assignment doesn't trigger React state updates. Use the native setter:

```bash
browser_execute "
const input = document.querySelector('input[type=\"email\"]');
const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
setter.call(input, 'user@example.com');
input.dispatchEvent(new Event('input', { bubbles: true }));
input.dispatchEvent(new Event('change', { bubbles: true }));
"
```

### Click Button

```bash
browser_execute "document.querySelector('button[type=\"submit\"]').click()"
```

---

## Password Manager / 2FA (Bitwarden etc.)

WebDroid's floating overlay blocks touches to other apps (Bitwarden, Authenticator, etc.).
When a login form is detected, a 🔑 button appears in the window header.

**Flow:**
1. Navigate to a login page → 🔑 button appears automatically in the header
2. Tap 🔑 → WebDroid hides all overlays so Bitwarden/2FA can receive touches
3. Use Bitwarden autofill or enter 2FA code normally
4. A notification appears when the dialog closes — tap it to restore the WebDroid window

If the 🔑 button doesn't appear (form not detected), use the stash feature instead:
drag the bubble to the top edge of the screen to temporarily hide it.

---

## Google Login

Google blocks WebView by default, but `browser_ua_google` works around this.

```bash
browser_ua_google
browser_goto "https://accounts.google.com/..."
```

Fill email and click Next using Google's element IDs:

```bash
browser_execute "
const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
setter.call(document.querySelector('input[type=\"email\"]'), 'user@gmail.com');
document.querySelector('input[type=\"email\"]').dispatchEvent(new Event('input', {bubbles:true}));
"
browser_execute "document.getElementById('identifierNext').click()"

# Wait for password screen, then:
browser_execute "document.getElementById('passwordNext').click()"
```

**Note:** Enter the password manually by tapping the WebDroid bubble — don't pass it through scripts.

After login, switch back to desktop UA if needed:
```bash
browser_ua_default
```

---

## Performance

### Use execute Instead of eval

When you don't need return value:
```bash
# Slower
browser_eval "console.log('test')"

# Faster
browser_execute "console.log('test')"
```

---

## Debugging

### Take Screenshots at Each Step

```bash
browser_goto "https://example.com"
browser_screenshot step1.png

browser_execute "document.querySelector('button').click()"
sleep 2
browser_screenshot step2.png
```

### Watch Logs

```bash
adb logcat -s AutomationService:D | grep "page_"
```

---

## Limitations

- No file upload
- No popup windows (new window/tab)
- No downloads
