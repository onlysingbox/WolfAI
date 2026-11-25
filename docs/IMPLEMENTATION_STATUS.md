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
- AI model selection UI: automatic, GLM-4.7-Flash, Gemma 4 26B, Gemini 2.5 Flash, Groq Llama 3.3, and OpenRouter free model.\n- Worker provider adapters and free-only fallback routing added for Cloudflare Workers AI, Gemini, Groq, and OpenRouter. External providers remain unavailable until their API keys are configured as Worker secrets. DeepSeek API is deliberately disabled because a reliably free, no-billing API tier has not been verified.\n- Provider responses return reported usage metadata when available; otherwise quota is explicitly unknown. The app does not fabricate quota counts.
- Storage usage panel for app-private folders, real device free-space display, and 80%/90% warnings. The 60 GB category allocations are not yet finalized in code.

## CI verification
- GitHub Actions Android build runs 10, 11, 12 and 13 completed successfully.
- The latest workflow adds `node --check src/worker.js` so backend syntax is validated as well as Android compilation. Run 14 is the verification run for that workflow change.

## Not implemented or not end-to-end verified
- Standalone pairing that does not require Telegram.
- Fixed production signing key compatible with the first installed APK.
- Server-side production deployment of the new companion endpoints.
- Exact 60 GB per-category allocations and complete file management.
- A complete provider quota dashboard with reset times and historical per-model usage. Provider APIs do not consistently expose this information; currently only returned usage metadata is forwarded, not a verified remaining-quota counter.
- General agent task proposals, approval UI, durable execution and result verification inside Android.
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
