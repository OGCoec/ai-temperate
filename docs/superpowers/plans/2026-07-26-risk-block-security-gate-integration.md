# Risk Block Security Gate Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every ordinary-user and administrator `RISK_BLOCKED` response to a dedicated, non-retryable Block security gate.

**Architecture:** Keep the backend `NetworkRiskScorePolicy` unchanged: `baseScore < 40 || finalScore < 20` produces `RISK_BLOCKED`. Each frontend owns an isolated navigation presenter and page. PreAuth, WebRTC recovery, application startup, and business-request handling all call the presenter so a block cannot fall through to ordinary error UI or replay the rejected request.

**Tech Stack:** UniApp/Vue single-file components, JavaScript request clients, Node contract tests.

---

### Task 1: Lock the Block contract before runtime changes

**Files:**
- Modify: `fornted/common/auth/network-risk-contract.test.cjs`
- Modify: `myuniappadmin/common/admin/admin-network-risk-contract.test.cjs`

- [ ] Add ordinary-side assertions requiring `RISK_BLOCKED` to use `uni.reLaunch`, a custom-navigation page, H5 history locking, no retry control, and no API calls from the page.
- [ ] Add administrator-side assertions with the same security guarantees.
- [ ] Defer the two `npm run test:auth-network-risk` commands until the user authorizes the project’s second validation phase.

### Task 2: Integrate the ordinary Block gate

**Files:**
- Create: `fornted/common/auth/risk-block-navigation.js`
- Create: `fornted/pages/risk/blocked.vue`
- Modify: `fornted/pages.json`
- Modify: `fornted/App.vue`
- Modify: `fornted/common/auth/pre-auth.js`
- Modify: `fornted/common/auth/http-client.js`
- Modify: `fornted/pages/risk/webrtc-failed.vue`

- [ ] Implement `presentRiskBlock(error)` with exact-code matching, route single-flight protection, and `uni.reLaunch`.
- [ ] Register `/pages/risk/blocked` with `navigationStyle: "custom"`.
- [ ] Build the approved amber Block page without buttons, API calls, score details, or automatic exit.
- [ ] Intercept H5 browser history and UniApp/Android back actions while the page is active.
- [ ] Invoke the presenter from PreAuth, startup WebRTC, WebRTC recovery/failure-page requests, and ordinary business-request errors.
- [ ] Preserve the original rejected request as rejected; do not retry it after navigation.

### Task 3: Integrate the administrator Block gate

**Files:**
- Create: `myuniappadmin/common/admin/admin-risk-block-navigation.js`
- Create: `myuniappadmin/pages/risk/blocked.vue`
- Modify: `myuniappadmin/pages.json`
- Modify: `myuniappadmin/App.vue`
- Modify: `myuniappadmin/common/admin/admin-pre-auth.js`
- Modify: `myuniappadmin/common/admin/admin-http.js`
- Modify: `myuniappadmin/pages/risk/webrtc-failed.vue`

- [ ] Implement the isolated administrator presenter with the same exact-code and single-flight rules.
- [ ] Register the administrator Block page with custom navigation.
- [ ] Keep the page structurally aligned with the ordinary page while using administrator-specific identity copy.
- [ ] Invoke the presenter from administrator PreAuth, startup WebRTC, WebRTC recovery/failure-page requests, and administrator business-request errors.
- [ ] Keep all administrator login/session and risk-scoring logic unchanged.

### Task 4: Second-phase verification after explicit approval

**Files:**
- Test: `fornted/common/auth/network-risk-contract.test.cjs`
- Test: `myuniappadmin/common/admin/admin-network-risk-contract.test.cjs`

- [ ] Run `Push-Location fornted; npm run test:auth-network-risk; Pop-Location`.
- [ ] Run `Push-Location myuniappadmin; npm run test:auth-network-risk; Pop-Location`.
- [ ] In an isolated browser/account environment, verify ordinary and administrator HTTP 403 `RISK_BLOCKED` responses enter their corresponding pages, browser/Android back cannot escape, and no retry request is generated.
- [ ] Confirm 20–59 remains Challenge and 60+ remains Allow; confirm Block is still `baseScore < 40 || finalScore < 20`.
