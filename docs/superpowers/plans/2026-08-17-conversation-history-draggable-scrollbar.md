# Conversation History Draggable Scrollbar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore a visible, draggable conversation-history scrollbar while retaining automatic cursor pagination at the bottom of the H5 sidebar and Android drawer.

**Architecture:** Keep `UserRecentConversations` as the shared H5/Android scroll surface. It records the native `scroll-view` position and measured content/viewport heights, renders one overlaid thumb only when the list overflows, and writes a bounded `scroll-top` when the thumb is dragged or operated from the keyboard. The existing `scrolltolower` handler remains the sole history-pagination trigger.

**Tech Stack:** uni-app Vue Options API, `scroll-view`, scoped SCSS, Node built-in source-contract tests.

---

### Task 1: Describe the cross-platform thumb contract

**Files:**
- Modify: `fornted/pages/ai-chat/ai-chat-page-contract.test.cjs`

- [ ] **Step 1: Add a source-contract test before production code**

```js
test('recent conversations expose a visible draggable scrollbar that stays synchronized with the shared scroll view', () => {
	const recent = read('components/user/user-recent-conversations.vue')

	assert.match(recent, /:scroll-top="recentScrollTarget"[\s\S]*:show-scrollbar="false"[\s\S]*@scroll="handleRecentScroll"/)
	assert.match(recent, /class="recent-scrollbar-thumb"[\s\S]*role="scrollbar"/)
	assert.match(recent, /@mousedown\.stop\.prevent="startRecentScrollbarDrag"/)
	assert.match(recent, /@touchmove\.stop\.prevent="moveRecentScrollbarDrag"/)
	assert.match(recent, /recentScrollbarThumbStyle\(\)[\s\S]*transform/)
	assert.match(recent, /handleRecentScrollbarKeydown\(event\)/)
})
```

- [ ] **Step 2: Verify after the user authorizes second-phase validation**

```cmd
cd /d C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test pages\ai-chat\ai-chat-page-contract.test.cjs
```

Expected before implementation: this test fails because the component has no custom thumb or scroll position synchronization.

### Task 2: Add the shared scrollbar and drag synchronization

**Files:**
- Modify: `fornted/components/user/user-recent-conversations.vue`

- [ ] **Step 1: Bind native scroll position and hide only its visual scrollbar**

```vue
<scroll-view
	ref="recentList"
	class="recent-list"
	scroll-y
	:scroll-top="recentScrollTarget"
	:show-scrollbar="false"
	@scroll="handleRecentScroll"
	:lower-threshold="96"
	@scrolltolower="requestLoadMore"
>
```

- [ ] **Step 2: Render an overflow-only, keyboard-accessible thumb**

Render a `button` with `role="scrollbar"`, an accessible label, current/min/max ARIA values, keyboard handling, H5 mouse drag handlers, and Android touch drag handlers. It must only appear when measured content height exceeds the viewport.

- [ ] **Step 3: Calculate thumb metrics and map drag distance to `scroll-top`**

Store viewport height, content height, current top, and a drag start point in component-local state. Keep the thumb height proportional to the viewport/content ratio with a 40px minimum. Clamp all programmatic scroll targets to `0..(contentHeight - viewportHeight)`.

- [ ] **Step 4: Measure after list/viewport changes and clean up H5 listeners**

Use `uni.createSelectorQuery().in(this)` to measure the shared list and content wrapper. Register H5 `resize`, `mousemove`, and `mouseup` listeners in `mounted`, and remove them in `beforeUnmount` / `beforeDestroy`.

### Task 3: Release verification after explicit approval

**Files:**
- Generated outside source control: `fornted/unpackage/dist/build/web/`

- [ ] **Step 1: Build the Web output in HBuilderX**

Use **发行 → 网站-PC Web或手机H5**. The current HBuilderX output path is:

```text
C:\Users\damn\Desktop\ai-temperate-main\fornted\unpackage\dist\build\web
```

- [ ] **Step 2: Run the contract test only after explicit second-phase approval**

```cmd
cd /d C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test pages\ai-chat\ai-chat-page-contract.test.cjs
```

- [ ] **Step 3: Upload only the generated `web` folder to Cloudflare Pages**

Use Cloudflare Pages **Create a new deployment → Production** and drag:

```text
C:\Users\damn\Desktop\ai-temperate-main\fornted\unpackage\dist\build\web
```

Do not upload the frontend source tree, `node_modules`, test files, or source maps.

### Scope and validation boundary

- Do not change the backend cursor API or administrator UI.
- Do not create a worktree or commit automatically; the user asked for direct edits in the active workspace.
- Do not run tests, compile, package, or deploy during this first-phase code change. The project requires explicit second-phase authorization for those actions.
