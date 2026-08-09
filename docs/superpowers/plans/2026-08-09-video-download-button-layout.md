# Video Download Button External Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Visually separate the generated-video download button from the black video frame and center it immediately below the frame.

**Architecture:** Preserve the current DOM order because the button is already a sibling of `attachment-media-frame`. Move the visible card boundary from the outer video attachment container to the media frame through scoped CSS, then update the existing source contract to guard that boundary.

**Tech Stack:** Vue Options API, uni-app H5 conditional compilation, SCSS, Node.js built-in test runner.

---

## File map

- Modify `fornted/components/user/workspace/user-video-download-contract.test.cjs`: assert that the outer video card is transparent, the media frame owns the visual boundary, and the button is centered below it.
- Modify `fornted/components/user/workspace/user-chat-panel.vue`: adjust only the three existing video layout selectors.

No template, JavaScript download behavior, image layout, backend API, OSS, FC, Java, or YAML changes are required.

### Task 1: Guard the external centered layout

**Files:**
- Modify: `fornted/components/user/workspace/user-video-download-contract.test.cjs`
- Read: `fornted/components/user/workspace/user-chat-panel.vue:3236-3256`

- [x] **Step 1: Add the layout contract assertions**

Append these assertions to the existing layout test:

```js
const videoCardRule = source.match(/\.attachment-card\.is-video \{[^}]+\}/)?.[0] || ''
assert.match(videoCardRule, /overflow: visible;/)
assert.match(videoCardRule, /border: 0;/)
assert.match(videoCardRule, /border-radius: 0;/)
assert.match(videoCardRule, /background: transparent;/)
const videoFrameRule = source.match(/\.attachment-media-frame\.is-video \{[^}]+\}/)?.[0] || ''
assert.match(videoFrameRule, /overflow: hidden;/)
assert.match(videoFrameRule, /border: 1px solid #313a35;/)
assert.match(videoFrameRule, /border-radius: 12px;/)
assert.match(source, /\.video-download-button \{[^}]*margin: 10px auto 0;/)
```

- [ ] **Step 2: Defer RED execution until phase-two authorization**

The project currently authorizes source changes only. When the user explicitly authorizes local tests, run from `fornted/`:

```powershell
node --test components/user/workspace/user-video-download-contract.test.cjs
```

Expected before the CSS implementation: the new layout assertions fail because the black boundary still belongs to `.attachment-card.is-video` and the button is right-aligned.

### Task 2: Move the visual boundary to the media frame

**Files:**
- Modify: `fornted/components/user/workspace/user-chat-panel.vue:3236-3256`
- Test: `fornted/components/user/workspace/user-video-download-contract.test.cjs`

- [x] **Step 1: Make the outer video attachment container layout-only**

Replace the existing `.attachment-card.is-video` rule with:

```scss
.attachment-card.is-video { width: min(100%, 720px); max-width: 100%; overflow: visible; justify-self: center; border: 0; border-radius: 0; background: transparent; }
```

- [x] **Step 2: Give the media frame the visible video boundary**

Replace the existing `.attachment-media-frame.is-video` rule with:

```scss
.attachment-media-frame.is-video { width: 100%; max-width: 720px; max-height: min(68vh, 1080px); margin: 0 auto; overflow: hidden; border: 1px solid #313a35; border-radius: 12px; background: #000; box-sizing: border-box; }
```

- [x] **Step 3: Center the button below the frame**

Change only the margin in `.video-download-button`:

```scss
.video-download-button { min-height: 36px; margin: 10px auto 0; padding: 0 12px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; border-radius: 10px; color: #a7e6c9; font-size: 12px; font-weight: 700; }
```

Do not change the download method, disabled state, error copy, Blob cleanup, or the `720px × 1080px` constraints.

### Task 3: Review and verify after explicit phase-two authorization

**Files:**
- Verify: `fornted/components/user/workspace/user-chat-panel.vue`
- Verify: `fornted/components/user/workspace/user-video-download-contract.test.cjs`

- [x] **Step 1: Perform the first-phase static diff review**

```powershell
git diff --check -- fornted/components/user/workspace/user-chat-panel.vue fornted/components/user/workspace/user-video-download-contract.test.cjs
git diff -- fornted/components/user/workspace/user-chat-panel.vue fornted/components/user/workspace/user-video-download-contract.test.cjs
```

Expected: no whitespace errors; only the video layout selectors and layout contract assertions change in this follow-up.

- [ ] **Step 2: Run the focused contract after phase-two authorization**

From `fornted/`:

```powershell
node --test components/user/workspace/user-video-download-contract.test.cjs
```

Expected: all video download contract tests pass.

- [ ] **Step 3: Run the existing chat suite after phase-two authorization**

From `fornted/`:

```powershell
npm run test:chat
```

Expected: all local chat tests pass without contacting the backend, OSS, xAI, Redis, RabbitMQ, or PostgreSQL.
