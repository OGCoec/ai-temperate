# ChatGPT-Style HTML Preview Sandbox Implementation Plan

> **For Codex:** Execute this plan incrementally in the current worktree. Preserve unrelated user changes. The repository's AGENTS.md first-stage rule overrides normal TDD execution: write test contracts before production code, but do not run tests, builds, dependency checks, browsers, deployments, or external connections until the user explicitly approves second-stage verification.

**Goal:** Replace the restrictive Blob HTML preview with a ChatGPT-style code/preview experience backed by a separately hosted, message-isolated HTML runtime that supports Three.js and other HTTPS CDN dependencies.

**Architecture:** The main uni-app H5 frontend loads a static sandbox shell from a separate `pages.dev` origin. A versioned `postMessage` protocol delivers HTML to the shell, which recreates a nested sandboxed `srcdoc` iframe for each render and forwards only sanitized status, size, navigation, and runtime-error messages. The existing code block owns the view toggle and full-screen UI; no backend Java code executes or constructs frontend markup.

**Tech Stack:** uni-app Vue SFC, Vite compile-time constants, browser `postMessage`, sandboxed iframes, static ES modules, Node built-in HTTPS server and `node:test` contract tests.

---

## Project constraints

- Do not use the Codex in-app browser or create a hidden WebView.
- Do not run any test/build command during first-stage implementation.
- Do not deploy Cloudflare Pages, create DNS records, or contact external services during first stage.
- Preserve current uncommitted changes in `fornted/index.html`, `fornted/package.json`, `fornted/package-lock.json`, `fornted/components/user/workspace/user-markdown-message.vue`, and the AI response parser tests.
- `fornted/common/aichat/ai-markdown-renderer-contract.test.cjs` already has user edits; change only the obsolete Blob-preview assertions.
- Use `apply_patch` for all edits.

## Task 1: Define main-app origin configuration and message contracts

**Files:**

- Create: `fornted/common/aichat/ai-html-preview-config.js`
- Create: `fornted/common/aichat/ai-html-preview-config.test.cjs`
- Create: `fornted/common/aichat/ai-html-preview-protocol.js`
- Create: `fornted/common/aichat/ai-html-preview-protocol.test.cjs`
- Modify: `fornted/vite.config.js`
- Modify: `fornted/index.html`

**Step 1: Write config contract tests without executing them**

Cover exact HTTPS origins, localhost HTTPS, rejection of credentials/path/query/fragment/non-HTTPS, and empty configuration. Reuse `ai-code-test-loader.cjs` so the test runs with the existing Node toolchain later.

**Step 2: Write protocol contract tests without executing them**

Cover secure 128-bit channel IDs, byte-length enforcement, known message types, wrong channel/version/source rejection, sanitized runtime errors, and exact iframe URL hash construction.

**Step 3: Implement the smallest config/protocol modules**

Expose constants for protocol version, message source, maximum 1 MiB HTML payload, 4 KiB error text, and 8/15 second timeouts. Require exact origin matching and use `crypto.getRandomValues`; do not provide a weak random fallback.

**Step 4: Add the compile-time preview origin**

In `fornted/vite.config.js`, resolve `AI_HTML_PREVIEW_ORIGIN` or use `https://localhost:4174` for development and `https://ai-temperate-html-preview.pages.dev` for production. Reject malformed origins before Vite starts. Define the normalized value as `__AI_HTML_PREVIEW_ORIGIN__`.

**Step 5: Extend only `frame-src` in the main CSP**

Add `https://localhost:4174`, `https://127.0.0.1:4174`, and `https://ai-temperate-html-preview.pages.dev` to the existing `fornted/index.html` meta CSP. Preserve the user's `ihaveaplan` connect source and `blob:` image source.

**Second-stage verification command (do not run now):**

```powershell
Set-Location fornted
node --test common/aichat/ai-html-preview-config.test.cjs common/aichat/ai-html-preview-protocol.test.cjs
```

Expected second-stage result: all origin and protocol contracts pass.

## Task 2: Build the independent static sandbox shell

**Files:**

- Create: `cloudflare/html-preview-sandbox/package.json`
- Create: `cloudflare/html-preview-sandbox/README.md`
- Create: `cloudflare/html-preview-sandbox/public/index.html`
- Create: `cloudflare/html-preview-sandbox/public/sandbox-shell.css`
- Create: `cloudflare/html-preview-sandbox/public/message-protocol.js`
- Create: `cloudflare/html-preview-sandbox/public/runtime-document.js`
- Create: `cloudflare/html-preview-sandbox/public/sandbox-shell.js`
- Create: `cloudflare/html-preview-sandbox/public/_headers`
- Create: `cloudflare/html-preview-sandbox/public/_redirects`
- Create: `cloudflare/html-preview-sandbox/scripts/serve-local.mjs`
- Create: `cloudflare/html-preview-sandbox/test/message-protocol.test.mjs`
- Create: `cloudflare/html-preview-sandbox/test/runtime-document.test.mjs`
- Create: `cloudflare/html-preview-sandbox/test/static-security-contract.test.mjs`

**Step 1: Write sandbox protocol and document tests without executing them**

Tests must cover parent-origin allowlisting, fragment parsing, strict message validation, full-document and fragment wrapping, bridge insertion before user scripts, `type="module"` preservation, source containing literal `</script>` text, and error truncation.

**Step 2: Write static security contract tests without executing them**

Assert the shell CSP is compatible with inherited `srcdoc` execution for HTTPS modules, network calls and Workers, while `frame-ancestors` lists only the intended main-app origins, no Cookie is configured, referrers are disabled, and no top-navigation/download/camera/microphone permission is granted.

**Step 3: Implement the shared sandbox protocol**

The shell reads `channelId` and `parentOrigin` from the URL fragment, validates the parent against a fixed exact allowlist, and targets that origin explicitly. It accepts only `render` and `dispose` from `window.parent` with the matching channel/version/source.

**Step 4: Implement runtime document construction**

Inject the isolated runtime CSP, viewport, no-referrer meta, and a compact bridge before all user scripts. The bridge reports `rendered`, `runtime-error`, `navigation`, and clamped `ResizeObserver` height events to the shell. It does not serialize arbitrary thrown objects or expose stack traces containing query strings.

**Step 5: Implement disposable nested iframe rendering**

For each `renderId`, destroy the prior iframe, create a new sandboxed iframe, assign generated HTML through `srcdoc`, and forward only validated runtime messages. Dispose removes the iframe and references. No source HTML is written to logs or storage.

**Step 6: Add a zero-dependency local HTTPS server**

Use Node built-ins only. Read the existing `LOCAL_HTTPS_P12_PATH` and `SERVER_SSL_KEY_STORE_PASSWORD` environment variables, serve only files under `public`, prevent path traversal, return no-store and security headers, and bind to `127.0.0.1:4174`.

**Second-stage verification command (do not run now):**

```powershell
Set-Location cloudflare/html-preview-sandbox
node --test test/message-protocol.test.mjs test/runtime-document.test.mjs test/static-security-contract.test.mjs
```

Expected second-stage result: protocol, runtime-document, and header contracts pass.

## Task 3: Replace the Blob preview component with the remote sandbox client

**Files:**

- Modify: `fornted/components/user/workspace/user-markdown-html-preview.vue`
- Modify: `fornted/common/aichat/ai-html-preview-document.js`
- Modify: `fornted/common/aichat/ai-html-preview-document.test.cjs`
- Modify: `fornted/common/aichat/ai-markdown-renderer-contract.test.cjs` only in the HTML preview test block
- Create: `fornted/common/aichat/ai-html-preview-component-contract.test.cjs`

**Step 1: Replace obsolete Blob assertions before production edits**

Update the existing renderer contract so it requires the separate-origin sandbox tokens, `referrerpolicy="no-referrer"`, exact `postMessage` target origin, cleanup listeners/timers, and absence of Blob document construction in the main component. Preserve all AI response-parser assertions elsewhere in the file.

**Step 2: Add lifecycle/state contract tests without executing them**

Cover `idle/connecting/rendering/ready/warning/error`, 8-second connection timeout, 15-second slow-render warning, wrong window/origin/channel rejection, `dispose` on unmount, and non-H5 fallback.

**Step 3: Reduce the legacy document module to language detection**

Keep `isAiHtmlPreviewLanguage`. Remove the main-origin HTML builder and its restrictive CSP because document construction now belongs only to the isolated sandbox.

**Step 4: Implement the remote preview client**

Create a channel at mount, build the exact sandbox URL, install one window message listener, verify `event.source` and `event.origin`, send `render` only after `ready`, and send `dispose` before teardown. Show explicit connecting, rendering, warning, and error states. Clamp inline preview height while allowing full-height layout from the parent.

**Step 5: Keep failure safe and understandable**

Invalid config, missing Web Crypto, oversized HTML, connection timeout, protocol mismatch, and runtime errors must never become an unexplained blank panel. Do not fall back to the old Blob implementation.

**Second-stage verification command (do not run now):**

```powershell
Set-Location fornted
node --test common/aichat/ai-html-preview-document.test.cjs common/aichat/ai-html-preview-component-contract.test.cjs common/aichat/ai-markdown-renderer-contract.test.cjs
```

Expected second-stage result: the component contracts require isolated-origin messaging and reject the old Blob path.

## Task 4: Complete the ChatGPT-style toolbar and full-screen experience

**Files:**

- Modify: `fornted/components/user/workspace/user-markdown-code-block.vue`
- Modify: `fornted/common/aichat/ai-html-preview-component-contract.test.cjs`

**Step 1: Add UI contract assertions without executing them**

Require the 74×36 code/preview group, 36×36 buttons, 38px indicator movement, ARIA pressed/disabled state, local inline SVG icons, copy/full-screen/close/download actions, Escape handling, focus restoration, scroll restoration, and reduced-motion rules.

**Step 2: Replace text glyphs with independent inline SVG icons**

Use simple stroke icons for code, preview, copy, expand, close, and download. Keep icons decorative inside buttons with explicit accessible button labels.

**Step 3: Add full-screen state without remounting the preview**

Apply a fixed full-window class to the existing code-block root so the same preview iframe survives entry/exit. In full-screen, expose close, code/preview, copy, and download controls. Escape closes; focus moves to close and returns to expand; body overflow is restored on every exit path.

**Step 4: Refine frequent interaction motion**

Use only transform/color/opacity transitions, keep the indicator at 200ms cubic-bezier(0.4,0,0.2,1), button press at 120ms `scale(.97)`, gate hover styling to fine pointers, and remove movement under reduced-motion preferences.

**Step 5: Preserve streaming and code-change safety**

Streaming or changed code returns to code mode and exits full-screen. Preview remains unavailable until a complete non-empty HTML block exists.

**Second-stage verification command (do not run now):**

```powershell
Set-Location fornted
node --test common/aichat/ai-html-preview-component-contract.test.cjs common/aichat/ai-markdown-renderer-contract.test.cjs
```

Expected second-stage result: toolbar, accessibility, full-screen lifecycle, and motion contracts pass.

## Task 5: Register tests and perform first-stage static review

**Files:**

- Modify: `fornted/package.json` only in `test:ai-code`
- Review: all files changed by Tasks 1-4

**Step 1: Register the new main-app tests**

Append config, protocol, and component contract tests to `test:ai-code`. Preserve the user's `test:ai-response` script and dependency changes.

**Step 2: Review diffs without executing validation**

Inspect only changed hunks. Confirm no Java file contains frontend code, no Secret or token was added, main-site CSP changed only at `frame-src`, all message sends use an explicit origin, and user-owned unrelated changes remain intact.

**Step 3: Report first-stage delivery honestly**

List delivered source files and state that tests, builds, local HTTPS startup, Chrome interaction, Cloudflare deployment, DNS, and Three.js runtime verification were not executed.

## Optional second-stage verification sequence

Run only after the user explicitly confirms this exact scope and acknowledges local process/file writes:

1. Run the targeted Node contract tests from Tasks 1-4.
2. Start the sandbox local HTTPS server on `127.0.0.1:4174` using test/local certificates.
3. Start the existing H5 development server on `127.0.0.1:3000`.
4. Connect only to the user's external Chrome extension browser and confirm the browser type is `extension`.
5. Verify the original Three.js water page, pointer interaction, dynamic import, runtime errors, code/preview switching, full-screen, copy, download, and focus return.
6. Stop both local servers. Do not deploy Cloudflare or change DNS unless separately authorized.
