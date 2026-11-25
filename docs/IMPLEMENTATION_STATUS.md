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
- AI model selection UI: automatic, GLM-4.7-Flash, Gemma 4 26B, Gemini 2.5 Flash, Groq GPT-OSS 120B, and OpenRouter free model.
- Worker provider adapters and free-only fallback routing added for Cloudflare Workers AI, Gemini, Groq, and OpenRouter. External providers remain unavailable until their API keys are configured as Worker secrets. DeepSeek API is deliberately disabled because a reliably free, no-billing API tier has not been verified.
- Provider responses return reported usage metadata when available; otherwise quota is explicitly unknown. The app does not fabricate quota counts.
- Storage usage panel for app-private folders, real device free-space display, and 80%/90% warnings. The 60 GB category allocations are not yet finalized in code.

## Mission proposal and gated execution API (source implemented, deployment pending)
- Authenticated Android companion endpoint: `POST /api/companion/missions` accepts `{\"text\":\"...\"}` and uses the configured free-only model router to create a JSON proposal.
- `GET /api/companion/missions` lists only the authenticated owner's missions.
- Plans are capped at 8 steps; capability values are allowlisted and unknown values become `manual_review`. All steps require approval. The endpoint stores proposals in `agent_runs` and `agent_steps` and writes an activity log.
- Mission creation is proposal-only. Invalid model JSON fails closed without creating a mission. After the whole plan is approved, the owner can prepare an individual step. The Worker converts only supported read-only capabilities into a typed action, validates its action type and required arguments, stores it, and returns the exact action for review. A separate explicit Android action executes that exact stored action. Supported initial actions are web search/open, private memory search, artifact search, finance summary, system health, and read-only GitHub listing/workflow/file reads. Writes and external side effects are not executable through this path.
- The route is protected by companion bearer authentication and MASTER OFF. A dedicated read-only execution wrapper now revalidates the stored action at execution time against its approved capability, strict argument allowlists, and owner approval metadata. It rejects write actions, sequence envelopes, capability mismatches, extra parameters, invalid dates/currency, unsafe URL targets, and GitHub path traversal. Mission web-page reads stream at most 1.5 MB instead of buffering an unbounded response.
- A preparation audit-log field-name defect was corrected; the event records the validated action type. Production use requires Worker deployment and the existing D1 migration `0002_agent_platform.sql` to be applied.

## Android mission UI (source committed; latest execution-controls build pending)
- The app calls the authenticated mission proposal API to create and list proposals.
- It displays the generated summary, steps, capability labels, and awaiting-approval status. MASTER OFF disables proposal creation.
- The owner can approve or reject a pending proposal from Android. Approval changes the run and steps to `approved_pending_execution`; rejection marks them `rejected`.
- For supported read-only steps, Android can request an exact prepared action, display its JSON details, and require a second explicit tap before executing that action. It refreshes per-step status and displays the result/error. Unsupported capabilities remain unexecutable and require manual review.

## AI provider implementation update (2026-11-25)
- Updated Groq from the retired `llama-3.3-70b-versatile` model to `openai/gpt-oss-120b` following Groq's August 16, 2026 shutdown notice for the old model.
- Confirmed the Cloudflare model identifiers against current Cloudflare model documentation: `@cf/zai-org/glm-4.7-flash` and `@cf/google/gemma-4-26b-a4b-it`.
- Added 25-second request timeouts to Gemini, Groq, and OpenRouter HTTP calls, plus OpenRouter application identification headers.
- Android model selection now reads the authenticated `/api/companion/status` provider catalog. Engines without a configured binding/key are disabled rather than presented as selectable.
- Added automated tests for configured-provider reporting, free-only routing order, and current model identifiers. These are configuration/routing tests, not live provider inference tests.
- Live Worker configuration check on 2026-11-25: `AI` binding is present; `GEMINI_API_KEY`, `GROQ_API_KEY`, and `OPENROUTER_API_KEY` are absent. Therefore only the two Cloudflare-bound engines are configured. No external-provider live tests can pass until the user adds those secrets.

## CI verification
- Earlier mission UI and atomic-write commits passed GitHub Actions, including `node --check src/worker.js` and Android `assembleDebug`.
- A Node built-in safety suite covers the mission action allowlist, capability matching, HTTPS/local-target rejection, finance date/currency validation, GitHub path traversal, extra-parameter rejection, MASTER OFF rejection, and owner-scoped lookup.
- A separate Node 22 `node:sqlite` integration suite executes the actual D1-compatible SQL statements against an in-memory SQLite database. It checks owner isolation, duplicate concurrent claims, sequential step ordering, wrong-owner approvals, active sibling steps, and stopped missions. The workflow runs both suites before the Android build.
- These tests exercise the SQL and state-transition contract locally; they do not contact the production Cloudflare D1 database or deploy the Worker. Live Cloudflare integration and device-level acceptance tests remain pending.

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
1. Stable release signing is now configured in GitHub Actions through repository secrets, and a signed release APK artifact was successfully built in run 45553866282. The previously installed debug-signed APK cannot be updated in place; the user has already approved one uninstall/reinstall when the final stable APK is ready. Every future release must keep using the same persisted signing key and the unchanged application ID.
2. The latest Worker source changes are not deployed to production. The owner has now authorized deployment, but the three external provider secrets are still absent. Deploy only after the provider setup is complete and then run authenticated live checks.
3. CI builds and mocked provider tests do not prove live model access or device-level behavior. Real provider tests, Android pairing/chat tests, and final device acceptance remain required.

Do not mark WOLF AI as ready until the provider setup, production deployment, live acceptance tests, and agreed product-scope checks are complete.

## Free provider setup
The following Cloudflare Worker secrets are optional and are never stored in the APK or committed to Git. As last checked on 2026-11-25, all three external secrets were absent from the live Worker settings:
- `GEMINI_API_KEY` for the Gemini 2.5 Flash free tier. Google states free-tier prompts may be used to improve its products; do not send sensitive/private material through this provider unless that policy is acceptable.
- `GROQ_API_KEY` for Groq's Free tier using `openai/gpt-oss-120b`; do not upgrade the Groq account to a paid tier if zero-cost use is required.
- `OPENROUTER_API_KEY` for the free-only model `meta-llama/llama-3.3-70b-instruct:free`. Provider access and rate limits can change.
- Existing `AI` binding continues to provide Cloudflare GLM and Gemma.

No DeepSeek API key is used, and no paid model or paid fallback is enabled. A user must configure each optional secret in Cloudflare before that provider can answer. Cloudflare documents a 10,000-Neuron daily free allocation; on Workers Free, requests fail after the allocation, while Workers Paid can bill usage above it. Keep the account on Workers Free if no charge is acceptable. Gemini's free tier may use submitted content to improve Google products. Groq must remain on its Free tier; OpenRouter is pinned to the `:free` model. Exact remaining quota is shown only when a provider exposes reliable usage information.

## Remaining agreed product work
The provider adapters are one subsystem, not completion of WOLF. The Android app still needs the device-control permission/service layer, contact and call workflows, messaging intents/connectors, file/document workspace, scheduled tasks and approvals, market-data adapters, searchable memory controls, security/permission audit, full master-off enforcement across background jobs, and end-to-end tests. These must be implemented and verified before a final release is called ready.
