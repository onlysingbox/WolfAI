# WOLF AI implementation status

Updated: 2026-11-28

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
- AI model selection UI reads the server provider catalog. In strict zero-billing mode the server exposes only automatic, Z.AI GLM-4.7-Flash and Groq GPT-OSS 120B.
- A server-side hard allowlist permits only Z.AI GLM-4.7-Flash and Groq GPT-OSS 120B, in that order. Cloudflare Workers AI is deliberately excluded from runtime routing because usage above its daily free allocation can be billable on paid Workers plans. Gemini and OpenRouter are also blocked, including requests from stale clients.
- Provider responses return reported usage metadata when available; otherwise quota is explicitly unknown. The app does not fabricate quota counts.
- Storage usage panel for app-private folders, real device free-space display, and 80%/90% warnings. The 60 GB category allocations are not yet finalized in code.
- Private long-term memory workspace in Android: authenticated owner-only listing, local search over the returned entries, and item deletion behind a confirmation dialog. The Worker endpoint validates the key, requires `confirm: true`, scopes the setting to the paired owner, and writes an activity log. The memory list is limited to 50 entries.
- Private cloud text-document workspace in Android: authenticated listing and owner-confirmed creation of Markdown/text artifacts in D1, separate from local phone files. This is not yet a full Word/PDF/Excel cloud editor.
- Private cloud documents support authenticated full-content retrieval by document ID and owner-scoped permanent deletion. Android provides explicit open and delete controls; deletion requires a confirmation dialog, MASTER ON, matching owner ID, and an audit log. Local document workspace now includes export actions for PDF (Android native text layout with RTL direction), DOCX and XLSX, with Android's system save picker. This is export from the current title/body editor, not a full office editor or cloud document conversion. File opening and layout need device-level acceptance testing.

## Mission proposal and gated execution API (source implemented, deployment pending)
- Authenticated Android companion endpoint: `POST /api/companion/missions` accepts `{\"text\":\"...\"}` and uses the configured free-only model router to create a JSON proposal.
- `GET /api/companion/missions` lists only the authenticated owner's missions.
- Plans are capped at 8 steps; capability values are allowlisted and unknown values become `manual_review`. All steps require approval. The endpoint stores proposals in `agent_runs` and `agent_steps` and writes an activity log.
- Mission creation is proposal-only. Invalid model JSON fails closed without creating a mission. After the whole plan is approved, the owner can prepare an individual step. The Worker converts only supported read-only capabilities into a typed action, validates its action type and required arguments, stores it, and returns the exact action for review. A separate explicit Android action executes that exact stored action. Supported initial actions are web search/open, private memory search, artifact search, finance summary, system health, and read-only GitHub listing/workflow/file reads. Writes and external side effects are not executable through this path.
- The route is protected by companion bearer authentication and MASTER OFF. A dedicated read-only execution wrapper now revalidates the stored action at execution time against its approved capability, strict argument allowlists, and owner approval metadata. It rejects write actions, sequence envelopes, capability mismatches, extra parameters, invalid dates/currency, unsafe URL targets, and GitHub path traversal. Mission web-page reads stream at most 1.5 MB instead of buffering an unbounded response.
- A preparation audit-log field-name defect was corrected; the event records the validated action type. Production use requires Worker deployment and the existing D1 migration `0002_agent_platform.sql` to be applied.

## Android mission UI (source committed; CI verification pending)
- The app calls the authenticated mission proposal API to create and list proposals.
- It displays the generated summary, steps, capability labels, and awaiting-approval status. MASTER OFF disables proposal creation.
- The owner can approve or reject a pending proposal from Android. Approval changes the run and steps to `approved_pending_execution`; rejection marks them `rejected`.
- For supported steps, Android can request an exact prepared action, display its JSON details, and require a second explicit tap before executing that action. It refreshes per-step status and displays the result/error. New controls allow pausing a mission between steps, resuming a paused mission, and retrying a failed step only after earlier steps complete. A retry clears the stale prepared action and forces a fresh preparation and second confirmation. Unsupported capabilities remain unexecutable and require manual review.

## AI provider implementation update (2026-11-28)
- Updated Groq from the retired `llama-3.3-70b-versatile` model to `openai/gpt-oss-120b` following Groq's August 16, 2026 shutdown notice for the old model.
- Strict zero-billing provider allowlist now permits only Z.AI `glm-4.7-flash` and Groq `openai/gpt-oss-120b`; automatic order is Z.AI then Groq. Gemini, OpenRouter, Cloudflare Workers AI, and all other model routes are blocked at the server dispatch boundary.
- Android model picker now exposes only automatic, Z.AI free, and Groq free. If a previously saved model is no longer allowed/configured, the app resets selection to automatic.
- Worker provider tests verify the catalog, automatic order, model IDs, and that blocked providers are rejected before network or AI calls.
- Live Worker settings confirm `ZAI_API_KEY` and `GROQ_API_KEY` secret bindings exist. Their secret values cannot be read, and live inference with either saved key has not yet been confirmed.

## CI verification
- Android local unit tests cover blank-query validation, safe market-quote formatting, exchange symbol validation, Bitpin ratio-to-percent conversion, and Bitpin Toman-to-Rial normalization. The Android verification workflow runs `:app:testDebugUnitTest` after Kotlin compilation. The latest run for the exchange UI/parser changes is pending.
- Earlier mission UI and atomic-write commits passed GitHub Actions, including `node --check src/worker.js` and Android `assembleDebug`.
- On 2026-11-28, Worker syntax/tests passed after enforcing the zero-billing provider allowlist (run `46174447426`). The Android Kotlin compile passed for the memory-workspace callbacks and UI (run `46171884423`); a newer compile for the cloud-document workspace was still running when this status file was updated.
- A Node built-in safety suite covers the mission action allowlist, capability matching, HTTPS/local-target rejection, finance date/currency validation, GitHub path traversal, extra-parameter rejection, MASTER OFF rejection, and owner-scoped lookup.
- A separate Node 22 `node:sqlite` integration suite executes the actual D1-compatible SQL statements against an in-memory SQLite database. It checks owner isolation, duplicate concurrent claims, sequential step ordering, wrong-owner approvals, active sibling steps, and stopped missions. Worker tests also cover mission pause/resume and failed-step retry gates. The workflow runs both suites before the Android build.
- These tests exercise the SQL and state-transition contract locally; they do not contact the production Cloudflare D1 database or deploy the Worker. A live, read-only exchange smoke test checks public Nobitex and Bitpin endpoints in GitHub Actions. Live Cloudflare integration and device-level acceptance tests remain pending.

## Not implemented or not end-to-end verified
- Standalone pairing that does not require Telegram.
- Fixed production signing key compatible with the first installed APK.
- Exact 60 GB per-category allocations and complete file management.
- A complete provider quota dashboard with reset times and historical per-model usage. Provider APIs do not consistently expose this information; currently only returned usage metadata is forwarded, not a verified remaining-quota counter.
- General agent missions are available through authenticated `/api/companion/missions` (GET lists up to 50 owner missions and step states; POST creates up to 8 proposal steps). The authenticated decision endpoint approves/rejects the plan. The first execution phase adds a prepare-and-confirm path for a narrow allowlist of read-only actions. Each prepared action is stored per step and requires a separate user tap to execute. This is not yet a general-purpose executor; messaging, calling, device control, purchases, deletion, file writes, GitHub writes, and other side effects remain unavailable. End-to-end execution tests on a paired Android device remain pending.
- Cloud Gmail/Google Calendar OAuth connectors, direct email or messaging APIs, and general browser automation. Basic Android intents can open an email draft, SMS, WhatsApp or Telegram conversation; Android's local calendar can be read and a prefilled event form opened, but the app does not directly send messages or connect to a cloud calendar.
- The crypto screen supports global CoinGecko/CoinPaprika lookup plus read-only public market quotes from Nobitex and Bitpin. Nobitex returns Rial-denominated stats; Bitpin's `IRT` quote prices are Toman-denominated, and the app normalizes both to Rial internally before displaying Rial and Toman. No account keys are used, and no order placement, balances, or trading actions are exposed. A live CI smoke test has verified the Nobitex BTC/IRT stats endpoint; Bitpin's live endpoint returned a top-level JSON array rather than the documented fixture wrapper, and the parser/smoke test were corrected to accept that actual response. Latest verification is pending. Saraf.app has no verified public market API integration in this build. These are market quotes, not technical-analysis or trading signals.
- Basic contact-name matching and confirmation-gated phone call intents are present; robust duplicate-contact disambiguation, in-app call state, and video calling are not implemented.
- Basic app launching, Android settings intents, and an opt-in Accessibility service for reading visible text, clicking matching labels, scrolling, and system navigation are present. This is not unrestricted or reliable cross-app automation.
- Full office editing for PDF, Word and Excel; the app can extract PDF text/OCR, read DOCX/XLSX text, edit the extracted text in its preview workspace, and export the current document text to PDF/DOCX/XLSX. It does not preserve original Word/Excel layout or edit spreadsheet formulas, tables and styles.
- Turn-taking live voice with interruption.
- End-to-end validation of the memory and cloud-document screens against the newly deployed Worker on a paired Android device.
- Full privacy, security, storage, and end-to-end device tests.
- MASTER OFF is enforced in the deployed Worker at the shared AI model boundary and for queued WOLF work; final device-level behavior still requires acceptance testing.

## Release blockers
1. Stable release signing is configured in GitHub Actions through repository secrets. WOLF AI 0.6.8 (versionCode 14) was built, signed, and verified successfully in run 46174690714; the direct APK artifact is `app-release.apk`. The previously installed debug-signed APK cannot be updated in place; the user has already approved one uninstall/reinstall when the final stable APK is ready. Every future release must keep using the same persisted signing key and the unchanged application ID.
2. Worker `wolfai` deployment `d20b4153a31540f1b6e7aafa4211b0f8` is live. Production D1 contains all 14 required application tables, and the one-minute cron trigger is active.
3. CI builds and mocked provider tests do not prove live model access or device-level behavior. Real provider tests, Android pairing/chat tests, and final device acceptance remain required.

Do not mark WOLF AI as ready until the provider setup, production deployment, live acceptance tests, and agreed product-scope checks are complete.

## Free provider setup
The following Cloudflare Worker secrets are optional and are never stored in the APK or committed to Git. As checked on 2026-11-28, the live Worker has the Z.AI and Groq secret bindings configured:
- Existing `ZAI_API_KEY` is configured for Z.AI `glm-4.7-flash`, whose model pricing is listed as free. Provider access and rate limits can change; no live inference test has yet confirmed the saved key.
- Existing `GROQ_API_KEY` is configured for Groq `openai/gpt-oss-120b` on its free tier. Do not upgrade the Groq account to a paid tier if zero-cost use is required; no live inference test has yet confirmed the saved key.
- Gemini and OpenRouter are not used. Their credentials are not required.
- The existing Cloudflare `AI` binding remains configured but is intentionally not called by WOLF AI in strict zero-billing mode, because usage beyond the free allocation can be billed on paid plans.

No DeepSeek API key is used, and no paid model or paid fallback is enabled. Existing `ZAI_API_KEY` and `GROQ_API_KEY` bindings are used by the deployed Worker. Cloudflare Workers AI, Gemini, and OpenRouter are deliberately excluded from runtime routing. Groq must remain on its Free tier. Neither external provider is guaranteed to have unlimited requests; if a free quota is exhausted, WOLF AI returns an error instead of switching to a paid model. Saved key presence is confirmed, but live inference remains unverified. Exact remaining quota is shown only when a provider exposes reliable usage information.

## Remaining agreed product work
The provider adapters are one subsystem, not completion of WOLF. The Android app still needs the device-control permission/service layer, contact and call workflows, messaging intents/connectors, file/document workspace, scheduled tasks and approvals, market-data adapters, searchable memory controls, security/permission audit, full master-off enforcement across background jobs, and end-to-end tests. These must be implemented and verified before a final release is called ready.
