# Thinking Orb Activity UI Implementation Plan

> **For agentic workers:** This plan is executed inline in the approved feature branch. The first delivery phase writes the implementation and contract tests but does not run tests or builds until the user explicitly authorizes the second verification phase.

**Goal:** Add a local, H5/Android-compatible Thinking Orb activity indicator whose visual state is driven by voice, model SSE, web-search source events, and context compaction status.

**Architecture:** Keep the backend activity contract unchanged. A small presentation module maps normalized events to one current Orb state and a live label/source target, while the existing research presenter continues to render the complete historical timeline. A Vue component hosts a local 2D Canvas engine in `renderjs`, uses the official per-state presets with an extra speed multiplier of `1`, and combines `prefers-reduced-motion`/Android animation-scale detection with a persisted manual reduce-motion preference.

**Tech Stack:** Vue 3 Options API, uni-app App-Vue/H5, renderjs, plain 2D Canvas, Node `node:test` contract tests, MIT-licensed Thinking Orbs drawing algorithms.

---

### Task 1: Activity presentation and motion preference contracts

**Files:**
- Create: `fornted/common/aichat/ai-activity-presentation.js`
- Create: `fornted/common/aichat/ai-activity-presentation.test.cjs`
- Create: `fornted/common/ui/ai-motion-preference.js`
- Create: `fornted/common/ui/ai-motion-preference.test.cjs`

- [x] Add failing contract tests for phase/status mapping, latest activity source correlation, system-following motion, and manual reduce override.
- [x] Implement bounded normalization and centralized mapping.

### Task 2: Local Canvas Orb component

**Files:**
- Create: `fornted/components/user/workspace/user-thinking-orb.vue`
- Create: `fornted/components/user/workspace/user-thinking-orb-render.js`
- Modify: `docs/third-party-licenses.md`

- [x] Add the nine official state painters and tuned 20/64 presets in a renderjs-safe local engine.
- [x] Add DPR clamping, visibility pause, deterministic reduced-motion frame, and cleanup.
- [x] Record the MIT attribution and pinned upstream revision.

### Task 3: Chat and composer integration

**Files:**
- Modify: `fornted/components/user/workspace/user-chat-panel.vue`

- [x] Replace the static model activity dot with the current mapped Orb and latest matching favicon/domain.
- [x] Add the composer Orb for voice states and context-compaction Orb for queued/running compression.
- [x] Add the manual “减少动态效果” toggle without allowing it to force animation against the system preference.

### Task 4: Static review handoff

- [x] Review changed files, confirm no backend/template strings were added to Java, and report tests/builds not executed in phase one.
