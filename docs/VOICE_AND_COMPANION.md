# WOLF AI voice and Android companion

## Product decision

Voice is a primary way to use WOLF, not just a way to issue commands. The owner must be able to have an ordinary, open-ended conversation in Persian or English, ask questions, think aloud, discuss everyday topics, and switch naturally from conversation to an action request.

## Interaction modes

1. Text chat in Telegram and future companion app.
2. Push-to-talk: Android speech recognition converts speech to text; WOLF responds in text and speech.
3. Turn-taking voice conversation: the app listens for a user turn, sends it to WOLF, speaks the answer, and returns to listening. The owner can interrupt or stop speech.
4. Action mode: the same conversation can propose an action. The app displays the exact action and asks for explicit approval before side effects.
5. Optional wake phrase: investigate only after the foreground voice experience works. Android background microphone and wake-word behavior is restricted and must be transparent, opt-in, and battery-conscious.

## Android companion responsibilities

- Native Android app, Kotlin.
- Microphone permission only when voice is started; explain why it is needed.
- Android SpeechRecognizer for speech input where available.
- Android TextToSpeech for spoken replies, with language/voice selection based on installed engines.
- Foreground service only for a user-started ongoing voice session where Android requires it.
- Clear listening/speaking/idle states, mute, stop, cancel, and privacy controls.
- Secure pairing with WOLF backend. Never embed Telegram bot tokens, GitHub tokens, Cloudflare API tokens, or other server secrets in the APK.
- Use HTTPS. Device credentials must be revocable and scoped to the companion API.
- Do not request Accessibility, contacts, call, notification, or broad file permissions until a concrete feature needs them. Ask separately for each sensitive permission.

## Backend responsibilities

- A dedicated companion API, separate from the Telegram webhook.
- One-time owner-approved pairing and revocable device sessions.
- Owner-only authorization on every request.
- Conversation context and memory shared with the existing WOLF agent, with a clear conversation/session identifier.
- Responses distinguish ordinary conversational text from proposed actions.
- Action proposals include a human-readable summary, exact target, requested permission and risk level.
- The backend never treats speech recognition as proof of owner approval for high-impact actions; require a visible confirmation in the companion app for sensitive actions.
- Audit pairing, sessions, requests, approvals and outcomes without storing raw microphone audio by default.

## Website and service access

WOLF should use the best supported route for each service:
- Official API or OAuth integration where available.
- Browser automation for websites that require interactive navigation.
- Read-only public web research where no authenticated integration is connected.

WOLF must not claim universal website access. CAPTCHAs, MFA, anti-bot controls, payment confirmation, unsupported interfaces and account permissions may require the owner to take over. Purchases, bookings, messages, publishing, deletion, financial operations and production changes require fresh approval.

## Delivery slices and acceptance tests

### A. Voice conversation MVP
- Pair one Android device securely.
- Start and stop a voice session from the app.
- Speak Persian and English; receive a spoken answer.
- Ordinary conversation works without requiring a command intent.
- Interrupt/stop speech and resume listening.
- No always-on microphone by default.

### B. Shared agent conversation
- Voice and Telegram use the same owner memory and agent identity.
- The companion can ask questions and receive clarifications.
- The assistant can propose a multi-step task, but cannot silently execute sensitive steps.

### C. Android actions
- Add capabilities incrementally: alarms/reminders, app launch, notifications, then other supported OS actions.
- Each permission is requested just in time.
- Show exact action and require approval where appropriate.
- Report the actual Android result.

### D. Browser and service integrations
- Start with safe browsing and page extraction.
- Add authenticated integrations through OAuth or official APIs.
- Add browser automation only with scoped sessions and explicit approval for side effects.
- Maintain per-integration health and honest capability status.

## Non-goals

- No unrestricted root access.
- No hidden microphone recording.
- No bypassing Android security, CAPTCHAs, MFA or service access controls.
- No claim that a phone-native alarm, call, message or setting changed until Android confirms it.
- No secrets hardcoded in the Android app.
