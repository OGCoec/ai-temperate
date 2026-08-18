# Conversation History Infinite Scroll Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the authenticated conversation history automatically fetch and append the next cursor page when its H5 sidebar or Android drawer reaches the bottom.

**Architecture:** Keep the current PostgreSQL keyset cursor API unchanged. `UserRecentConversations` is the single cross-platform scroll surface: it emits one guarded `load-more` request at the scroll threshold. `UserWorkspace` remains the owner of `nextCursor`, `hasMoreConversations`, request state, error state, and list append behavior.

**Tech Stack:** uni-app Vue Options API, `scroll-view`, Node built-in source-contract tests, Spring Boot and PostgreSQL cursor pagination (unchanged).

---

### Task 1: Lock the cross-platform behavior with a source contract

**Files:**
- Modify: `fornted/pages/ai-chat/ai-chat-page-contract.test.cjs`

- [ ] **Step 1: Write the failing test**

```js
test('recent conversations request the next cursor page when the shared list reaches its threshold', () => {
	const recent = read('components/user/user-recent-conversations.vue')

	assert.match(recent,
		/<scroll-view[\s\S]*:lower-threshold="96"[\s\S]*@scrolltolower="requestLoadMore"/)
	assert.match(recent,
		/requestLoadMore\(\)\s*\{[\s\S]*!this\.hasMore\s*\|\|\s*this\.loading[\s\S]*return[\s\S]*\$emit\('load-more'\)/)
	assert.match(recent, /正在加载更多/)
	assert.match(recent, /已加载全部会话/)
	assert.doesNotMatch(recent, /class="recent-more"/)
})
```

- [ ] **Step 2: Verify the red state after the user authorizes second-phase validation**

```cmd
cd /d C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test pages\ai-chat\ai-chat-page-contract.test.cjs
```

Expected before implementation: the test fails because `UserRecentConversations` has neither `@scrolltolower` nor `requestLoadMore`.

### Task 2: Turn the existing shared list into a threshold-triggered loader

**Files:**
- Modify: `fornted/components/user/user-recent-conversations.vue`

- [ ] **Step 1: Bind the native cross-platform lower-threshold event**

```vue
<scroll-view
	class="recent-list"
	scroll-y
	:lower-threshold="96"
	@scrolltolower="requestLoadMore"
>
```

- [ ] **Step 2: Replace the normal manual button with loading and completion states**

```vue
<view v-if="loading && conversations.length" class="recent-status" role="status">
	<text>正在加载更多…</text>
</view>
<view
	v-else-if="loaded && conversations.length && !hasMore && !error"
	class="recent-status"
>
	<text>已加载全部会话</text>
</view>
```

Keep the existing error card and its retry button. Remove the normal `recent-more` button and its CSS, because touch/click loading is no longer the primary interaction.

- [ ] **Step 3: Guard duplicate threshold events locally**

```js
methods: {
	requestLoadMore() {
		if (!this.hasMore || this.loading) return
		this.$emit('load-more')
	}
}
```

The parent already applies the same protection through `nextCursor` and `conversationLoading`; keeping the guard in both places prevents duplicate native events from sending overlapping HTTP requests.

- [ ] **Step 4: Verify the green state after the user authorizes second-phase validation**

```cmd
cd /d C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test pages\ai-chat\ai-chat-page-contract.test.cjs
```

Expected: all contract tests pass. No backend, database, administrator UI, or API route changes are required.

### Task 3: Package and publish the H5 artifact after approval

**Files:**
- Generated outside source control: `fornted/unpackage/dist/build/web/`
- Regenerated: `cloudflare/api-gateway/src/generated/h5-assets.js`
- Potentially modify after Pages acceptance: `cloudflare/api-gateway/wrangler.jsonc`

- [ ] **Step 1: Create the production H5 artifact in HBuilderX**

Open `C:\Users\damn\Desktop\ai-temperate-main\fornted` in HBuilderX, then use **发行 → 网站-PC Web或手机H5**. Confirm that the output directory is:

```text
C:\Users\damn\Desktop\ai-temperate-main\fornted\unpackage\dist\build\web
```

- [ ] **Step 2: Run the approved release checks and create the Worker asset manifest**

```cmd
cd /d C:\Users\damn\Desktop\ai-temperate-main\fornted
npm run generate:h5-edge-assets
npm run verify:esm-contracts
npm run test:chat
npm run test:release
npm run verify:h5-release -- --dir unpackage\dist\build\web
```

- [ ] **Step 3: Direct-upload only the generated H5 folder to Cloudflare Pages**

```cmd
cd /d C:\Users\damn\Desktop\ai-temperate-main\cloudflare\api-gateway
npx wrangler login
npx wrangler pages deploy ..\..\fornted\unpackage\dist\build\web --project-name=ai-temperate-frontend
```

Record the immutable `https://<deployment>.ai-temperate-frontend.pages.dev` address printed by Wrangler. Never upload the `fornted` source tree, `node_modules`, tests, or source maps.

- [ ] **Step 4: Point the Worker at the accepted Pages deployment and publish it only after explicit production approval**

Update `H5_PAGES_ORIGIN` in `cloudflare/api-gateway/wrangler.jsonc` to the recorded immutable Pages origin, then run:

```cmd
cd /d C:\Users\damn\Desktop\ai-temperate-main\cloudflare\api-gateway
npx wrangler deploy
```

The Pages upload alone does not change `https://niko000o.site`, because the Worker currently selects a specific Pages origin.

### Scope and validation boundary

- Do not create a worktree or commit automatically: the user explicitly requested direct changes in the active workspace while another upload is in progress.
- Do not run tests, build, package, or deploy in this implementation phase. The project requires explicit second-phase authorization for those commands.
- Keep the default page size at 20 and the server maximum at 50. The behavior change is automatic pagination, not unbounded loading.
