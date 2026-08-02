# Web Search Required Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hidden three-state web-search picker with a visible capability-gated two-state switch whose enabled state sends `webSearchMode: "REQUIRED"`.

**Architecture:** Keep the existing request field and Responses streaming implementation. Change only the frontend presentation and build-time availability default: the model capabilities still gate visibility, `OFF` remains the initial state, and the enabled switch maps to `REQUIRED`. The backend runtime feature gate remains an independent deployment safety control.

**Tech Stack:** uni-app Vue 3, JavaScript, SCSS, Node test runner, Vite.

---

### Task 1: Lock the two-state contract with failing tests

**Files:**
- Modify: `fornted/common/aichat/ai-conversation-web-search.test.cjs`
- Modify: `fornted/pages/ai-chat/ai-chat-page-contract.test.cjs`

- [ ] **Step 1: Add a build-default contract**

Read `fornted/vite.config.js` and assert that the build-time define treats every value except the literal string `false` as enabled:

```javascript
test('ordinary frontend builds expose web search unless explicitly disabled', () => {
	const vite = fs.readFileSync(path.join(__dirname, '..', '..', 'vite.config.js'), 'utf8')
	assert.match(vite,
		/process\.env\.AI_CONVERSATION_WEB_SEARCH_ENABLED !== 'false'/)
})
```

- [ ] **Step 2: Replace the old picker contract with a switch contract**

Add assertions to `ai-chat-page-contract.test.cjs`:

```javascript
assert.match(page, /role="switch"/)
assert.match(page, /:aria-checked="String\(webSearchRequired\)"/)
assert.match(page, /AI_CONVERSATION_WEB_SEARCH_MODES\.REQUIRED/)
assert.match(page, /AI_CONVERSATION_WEB_SEARCH_MODES\.OFF/)
assert.doesNotMatch(page, /<picker[\s\S]{0,300}webSearchOptions/)
```

- [ ] **Step 3: Run the two tests and verify RED**

Run:

```powershell
node --test fornted/common/aichat/ai-conversation-web-search.test.cjs fornted/pages/ai-chat/ai-chat-page-contract.test.cjs
```

Expected: FAIL because the Vite default still requires literal `true` and the page still renders the three-state picker.

### Task 2: Implement the required-search switch

**Files:**
- Modify: `fornted/vite.config.js`
- Modify: `fornted/components/user/workspace/user-chat-panel.vue`

- [ ] **Step 1: Make the frontend capability available by default**

Change the Vite define to retain an explicit kill switch while enabling ordinary builds:

```javascript
__AI_CONVERSATION_WEB_SEARCH_ENABLED__: JSON.stringify(
	process.env.AI_CONVERSATION_WEB_SEARCH_ENABLED !== 'false'
)
```

- [ ] **Step 2: Replace the picker template with an accessible switch**

Use the existing `webSearchAvailable` condition and render:

```vue
<button
	v-if="webSearchAvailable"
	class="web-search-toggle"
	:class="{ 'is-active': webSearchRequired }"
	type="button"
	role="switch"
	:aria-checked="String(webSearchRequired)"
	:disabled="generating"
	@click="toggleWebSearch"
>
	<text>联网搜索</text>
	<view class="web-search-track" aria-hidden="true">
		<view class="web-search-thumb"></view>
	</view>
</button>
```

- [ ] **Step 3: Map switch state to OFF or REQUIRED**

Remove the unused three-state options, index, label, and picker handler. Add:

```javascript
webSearchRequired() {
	return this.selectedWebSearchMode
		=== AI_CONVERSATION_WEB_SEARCH_MODES.REQUIRED
}
```

and:

```javascript
toggleWebSearch() {
	if (this.generating || !this.webSearchAvailable) return
	this.selectedWebSearchMode = this.webSearchRequired
		? AI_CONVERSATION_WEB_SEARCH_MODES.OFF
		: AI_CONVERSATION_WEB_SEARCH_MODES.REQUIRED
}
```

The existing request construction continues to normalize and send `selectedWebSearchMode`.

- [ ] **Step 4: Style the switch without adding timers or animation polling**

Add a compact 36px control beside the reasoning picker. Animate only the thumb transform and colors, preserve a visible focus state, and disable pointer interaction while generating.

- [ ] **Step 5: Run tests and verify GREEN**

Run:

```powershell
node --test fornted/common/aichat/ai-conversation-web-search.test.cjs fornted/pages/ai-chat/ai-chat-page-contract.test.cjs
```

Expected: PASS.

### Task 3: Verify deployment boundaries

**Files:**
- Verify: `ai-temperate-web/src/main/resources/application.yml`
- Verify: `fornted/components/user/workspace/user-chat-panel.vue`

- [ ] **Step 1: Check source formatting and stale picker references**

Run:

```powershell
git diff --check -- fornted/vite.config.js fornted/components/user/workspace/user-chat-panel.vue fornted/common/aichat/ai-conversation-web-search.test.cjs fornted/pages/ai-chat/ai-chat-page-contract.test.cjs
rg -n "webSearchOptions|selectedWebSearchModeIndex|selectedWebSearchModeLabel|selectWebSearchMode" fornted/components/user/workspace/user-chat-panel.vue
```

Expected: `git diff --check` succeeds and `rg` returns no stale picker references.

- [ ] **Step 2: Preserve the backend runtime kill switch**

Confirm production starts the backend with:

```text
AI_CONVERSATION_WEB_SEARCH_ENABLED=true
```

The test profile remains `false`, and the backend must continue rejecting `REQUIRED` when its runtime gate is disabled.

- [ ] **Step 3: Perform browser acceptance after rebuild and deployment**

For a model with `RESPONSES + WEB_SEARCH`, verify the switch appears beside reasoning. Turn it on and confirm the POST payload contains:

```json
{"webSearchMode":"REQUIRED"}
```

Confirm the same SSE can subsequently carry real `activity`, `source`, `reasoning_summary`, and `delta` events without any fabricated search status.
