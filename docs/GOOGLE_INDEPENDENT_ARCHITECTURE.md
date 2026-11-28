# Google-independent integration architecture

Updated: 2026-11-28

WOLF AI must not require Google Cloud, Google OAuth, Firebase, or a Google account for its core Android workflows. The Android app and Cloudflare Worker remain provider-neutral.

## Existing Android alternatives

- Calendar: use Android's Calendar Provider and standard `ACTION_INSERT` calendar intent. The user chooses the calendar app already installed on the device and confirms event creation there. Reading events uses Android's `READ_CALENDAR` permission. No Google Calendar API or OAuth client is required.
- Contacts and calling: use Android Contacts Provider with runtime permission. Contact resolution fails closed on ambiguous matches; calling is user-confirmed and uses Android call intents.
- Reminders and alarms: use Android AlarmClock intents and the app's local task/planner state. No Google Tasks or Calendar dependency.
- Files: use Android Storage Access Framework document pickers and app-private local storage. No Google Drive dependency.
- Email handoff: use `mailto:` intents to open the user's installed mail app with a prepared draft. The user reviews and sends it. This is not inbox synchronization or direct SMTP delivery.
- AI: keep the current Cloudflare Worker router and its strict free-provider allowlist. Do not add a paid fallback or a Google AI provider implicitly.

## Email integration target

A provider-neutral IMAP/SMTP connector can be added without Google OAuth:
- User supplies the mail provider's IMAP/SMTP host, ports, TLS mode, username, and an app-specific password or provider-issued credential.
- Credentials must be stored only in Android encrypted preferences or an encrypted server-side secret store; never in source control, chat, logs, or ordinary app settings.
- IMAP is read-only by default. SMTP sending requires a clear in-app review and explicit send confirmation.
- Do not assume Gmail, Yahoo, Outlook, or any provider host/port. Discover settings from the user's actual mail provider.
- Some providers block IMAP/SMTP or require app passwords. A provider-specific connector must be verified before claiming compatibility.

## Optional cloud storage target

Use WebDAV as the first provider-neutral cloud-file protocol candidate (for example, a self-hosted Nextcloud-compatible server). It is not enabled until the user supplies a server URL and explicitly configures credentials. Use HTTPS only, validate the host, and store credentials encrypted. Keep local file access fully functional if no cloud account is configured.

## Security and cost rules

- No Google Cloud project, Google OAuth consent screen, or Google API key is required for the workflows above.
- Never ask the user to paste passwords, app passwords, API keys, or refresh tokens into chat.
- No automatic account creation, paid subscription, or paid API fallback.
- External actions (sending email, uploading files, deleting remote files) require a preview and explicit user confirmation.
- Report an integration as complete only after compilation, automated tests, and an end-to-end test against a real provider/account.

## Current implementation boundary

The Android app already contains the local calendar, contacts, file picker/storage, alarm intents, and email-draft handoff described above. Direct IMAP inbox reading, direct SMTP sending, and WebDAV synchronization are future implementation work and must not be represented as already working.
