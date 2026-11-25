# WOLF AI implementation status

Updated: 2026-11-25

This file records verified implementation, not planned features.

## Implemented in the Android companion
- Kotlin/Jetpack Compose Android application, application ID `com.onlysingbox.wolfai`.
- Secure local storage for the companion device token using Android encrypted preferences.
- Telegram-issued one-time pairing code flow.
- Persian push-to-talk speech recognition and Persian text-to-speech.
- Local JSON chat history stored in the app-private files directory.
- MASTER OFF immediately cancels the active Android request and suppresses late replies.
- Server-side companion MASTER OFF endpoint is enforced at the shared AI model boundary for Android and Telegram AI requests; queued WOLF agent jobs are acknowledged and dropped while OFF, so they do not replay when re-enabled. Internal delayed-message cleanup remains operational.
- Conversation context is sent from local Android storage; companion chat turns are no longer written to D1 per message.
- AI model selection UI: automatic, GLM-4.7-Flash, Gemma 4 26B, Gemini 2.5 Flash, Groq Llama 3.3, and OpenRouter free model.
- Worker provider adapters and free-only fallback routing added for Cloudflare Workers AI, Gemini, Groq, and OpenRouter. External providers remain unavailable until their API keys are configured as Worker secrets. DeepSeek API is deliberately disabled because a reliably free, no-billing API tier has not been verified.
- Provider responses return reported usage metadata when available; otherwise quota is explicitly unknown. The app does not fabricate quota counts.
- Storage usage panel for app-private folders, real device free-space display, and 80%/90% warnings. The 60 GB category allocations are not yet finalized in code.

## Mission proposal and gated execution API (source implemented, deployment pending)
- Authenticated Android companion endpoint: `POST /api/companion/missions` accepts `{\"text\":\"...\"}` and uses the configured free-only model router to create a JSON proposal.
- `GET /api/companion/missions` lists only the authenticated owner's missions.
- Plans are capped at 8 steps; capability values are allowlisted and unknown values become `manual_review`. All steps require approval. The endpoint stores proposals in `agent_runs` and `agent_steps` and writes an activity log.
- Mission creation is proposal-only. Invalid model JSON fails closed without creating a mission. After the whole plan is approved, the owner can prepare an individual step. The Worker converts only supported read-only capabilities into a typed action, validates its action type and required arguments, stores it, and returns the exact action for review. A separate explicit Android action executes that exact stored action. Supported initial actions are web search/open, private memory search, artifact search, finance summary, system health, and read-only GitHub listing/workflow/file reads. Writes and external side effects are not executable through this path.
- The route is protected by companion bearer authentication and MASTER OFF. Production use requires Worker deployment and the existing D1 migration `0002_agent_platform.sql` to be applied.

## Android mission UI (source committed; latest execution-controls build pending)
- The app calls the authenticated mission proposal API to create and list proposals.
- It displays the generated summary, steps, capability labels, and awaiting-approval status. MASTER OFF disables proposal creation.
- The owner can approve or reject a pending proposal from Android. Approval changes the run and steps to `approved_pending_execution`; rejection marks them `rejected`.
- For supported read-only steps, Android can request an exact prepared action, display its JSON details, and require a second explicit tap before executing that action. It refreshes per-step status and displays the result/error. Unsupported capabilities remain unexecutable and require manual review.

## CI verification
- Earlier mission UI and atomic-write commits passed GitHub Actions, including `node --check src/worker.js` and Android `assembleDebug`.
- A Node built-in test suite now covers the mission action allowlist, capability matching, HTTPS/local-target rejection, finance date/currency validation, GitHub path traversal, and extra-parameter rejection. The workflow runs these tests before the Android build.
- The new hardened executor and test workflow are committed; their latest CI run must pass before these changes are considered verified.
- CI does not run live Worker/D1 integration tests or device-level acceptance tests.

## Not implemented or not end-to-end verified
- Standalone pairing that does not require Telegram.
- Fixed production signing key compatible with the first installed APK.
- Server-side production deployment of the new companion endpoints.
- Exact 60 GB per-category allocations and complete file management.
- A complete provider quota dashboard with reset times and historical per-model usage. Provider APIs do not consistently expose this information; currently only returned usage metadata is forwarded, not a verified remaining-quota counter.
- General agent missions are available through authenticated `/api/companion/missions` (GET lists up to 50 owner missions and step states; POST creates up to 8 proposal steps). The authenticated decision endpoint approves/rejects the plan. The first execution phase adds a prepare-and-confirm path for a narrow allowlist of read-only actions. Each prepared action is stored per step and requires a separate user tap to execute. This is not yet a general-purpose executor; messaging, calling, device control, purchases, deletion, file writes, GitHub writes, scheduling and other side effects remain unavailable. Production deployment, D1 migration, and end-to-end execution tests remain pending.
- Email, calendar, messaging, browser automation and OAuth connectors inside Android.
- Crypto/market analysis center and exchange data integration.
- Contacts resolution and confirmed phone/video calling.
- App launch/control, accessibility automation and supported Android settings controls.
- Document creation/editing for PDF, Word and Excel.
- Turn-taking live voice with interruption.
- Logo assets based on the owner's exact wolf image.
- Full privacy, security, storage, and end-to-end device tests.
- MASTER OFF is now enforced for model execution and queued WOLF work in source; production behavior remains unverified until the Worker is deployed with owner approval.

## Release blockers
1. The first APK was built with the GitHub runner's debug signing key. GitHub-hosted runners generate their debug keystore locally; it was not persisted in the repository or workflow artifact. A later APK signed with a different key cannot update that installation. A stable signing key must be established, and the first installation compatibility issue must be resolved without changing the application ID.
2. Worker source changes are not deployed to the production Cloudflare Worker. Production deployment requires explicit owner approval.
3. A successful CI build proves compilation only. It does not prove that Android permissions, backend pairing, live AI requests, or phone actions work on a real device.

Do not mark WOLF AI as ready until the release blockers and the agreed end-to-end acceptance tests are resolved.


## Free provider setup
The following Cloudflare Worker secrets are optional and are never stored in the APK or committed to Git:
- `GEMINI_API_KEY` for the Gemini free tier. Google states free-tier prompts may be used to improve its products; do not send sensitive/private material through this provider unless that policy is acceptable.
- `GROQ_API_KEY` for Groq's free developer tier, subject to provider rate limits.
- `OPENROUTER_API_KEY` for a free-model route. The configured model is explicitly suffixed `:free`; free catalog availability and limits can change.
- Existing `AI` binding continues to provide Cloudflare GLM and Gemma.

No DeepSeek API key is used, and no paid model or paid fallback is enabled. A user must configure each optional secret in Cloudflare before that provider can answer. Exact remaining quota is shown only when a provider exposes reliable usage information.

## Remaining agreed product work
The provider adapters are one subsystem, not completion of WOLF. The Android app still needs the device-control permission/service layer, contact and call workflows, messaging intents/connectors, file/document workspace, scheduled tasks and approvals, market-data adapters, searchable memory controls, security/permission audit, full master-off enforcement across background jobs, and end-to-end tests. These must be implemented and verified before a final release is called ready.
