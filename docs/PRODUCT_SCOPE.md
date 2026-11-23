# WOLF AI — Product Scope and Delivery Contract

WOLF AI is a private, owner-only, general-purpose personal agent. This document is the acceptance contract for the project. It is not a claim that all capabilities are already implemented.

## Operating principles

- The owner can use natural-language text or voice; technical commands are optional.
- The assistant plans multi-step work, chooses tools, executes permitted steps, checks results, and reports honestly.
- The owner must explicitly approve external side effects before they happen. Approval must show the exact target, content/changes, account/service, and consequences.
- Destructive, financial, security, production, publishing, communication and calling actions require fresh, action-specific approval.
- Never claim success until the connected service confirms it.
- Store task plans, step states, approvals, audit events, results and rollback references.
- Credentials stay in encrypted service secrets; never ask the owner to paste secrets into chat.
- Integrations are capability-scoped and least-privilege. A missing connector must be reported, not simulated.

## Required capability domains

### 1. Personal operations
- Create, edit, organize, search and summarize files and documents.
- Draft and manage email, calendar events, reminders, tasks and follow-ups.
- Generate and export useful documents and reports.

### 2. Servers, networks and infrastructure
- Inspect server health, services, logs, resource usage and connectivity.
- Diagnose VPN, routing, DNS, TLS, firewall and network problems.
- Propose infrastructure changes, show exact diffs and rollback plans, then apply only after approval.
- Monitor services and report incidents.

### 3. Software engineering
- Read, create, edit, review and test code in authorized repositories.
- Build websites, mobile/web applications, bots, APIs and integrations.
- Diagnose bugs, run checks and CI workflows, prepare patches and deploy only after explicit production approval.

### 4. Finance
- Track owner-entered or connected-account income and expenses.
- Build budgets, calculations, forecasts, comparisons and reports.
- No transfer, purchase, payment, trade or other financial transaction without explicit per-transaction approval and a supported regulated integration.

### 5. Automation
- Create scheduled and event-triggered workflows.
- Connect supported services and pass data between them.
- Include pause, cancel, retry, failure reporting and execution history.

### 6. Everyday services
- Research and compare travel, shopping and service options.
- Prepare itineraries, bookings and purchase plans.
- Do not place orders, book paid services or make payments without explicit approval.

### 7. Content production and publishing
- Draft text, create images, assist with video and design workflows, prepare channel posts.
- Preview exact media/caption/destination and publish only after approval.
- Respect rights, platform rules and content safety requirements.

### 8. Research and learning
- Search and compare reliable sources, cite evidence, summarize documents and datasets.
- Teach concepts, prepare learning plans, analyze books and information.
- Distinguish sourced facts from estimates and uncertainty.

## Voice, messaging and calling

These are required interaction capabilities, with messaging/calling delivery implemented after the general agent foundation:

1. Accept voice notes and convert speech to text.
2. Keep text, voice-input/text-output and two-way voice modes consistent with the owner's response-language setting.
3. For a request such as “message Ali”, resolve the recipient from verified contacts and ask for clarification if ambiguous.
4. Draft the exact message and show recipient, service and content.
5. Send only after the owner explicitly approves that exact draft.
6. Support connected messaging services such as Telegram and WhatsApp only to the extent their official APIs and account permissions allow.
7. Support initiating a phone call through a companion Android app and OS-supported intents/permissions. A Cloudflare Worker or ordinary Telegram bot cannot directly control the handset or silently place arbitrary calls.
8. Before calling, show the contact, phone number, call type and ask for approval. Report whether the call was initiated; never claim the other person answered unless confirmed.
9. Any recording, transcription or analysis of another person's call audio requires appropriate consent and legal compliance.

## Delivery order

1. General agent runtime: structured plans, durable multi-step runs, tool registry, permissions, approvals, audit, retries, result verification and rollback metadata.
2. Core integrations: GitHub read/write/test workflows; Cloudflare and infrastructure inspection/change workflows; file/document storage.
3. Personal operations and communication connectors: email, calendar, contacts and task management.
4. Research/browser, everyday services, content and finance reporting.
5. Automation scheduler, monitoring and proactive task execution.
6. Voice input and output, then messaging draft/approval/send.
7. Companion Android app for handset functions and supported calling.

Each integration is delivered as a working, tested vertical slice. A capability is marked **active** only after an end-to-end test against the real connected service. A database table, prompt, mock or planned connector does not count as an active capability.

## Current verified baseline (2026-11-23)

- Telegram private-owner chat and webhook.
- Cloudflare Worker, D1 and Workers AI (GLM/Gemma) conversational foundation.
- GitHub repository listing, workflow-run listing and file reading.
- Approval-gated GitHub text-file creation and updates. Updates capture the prior content and blob SHA before approval, then use SHA preconditions to reject concurrent modifications.
- Durable agent runs and up to five sequential tool steps, with per-step status stored in D1.
- Private D1-backed document creation/search and an owner-scoped finance ledger with approval-gated record creation and date-range summaries. Ledger entries are records only; no money is moved.
- The currently exposed tools cover health checks, GitHub, private documents and ledger reporting; this is not yet a general connector runtime for every product domain.
- D1 migration `0002_agent_platform.sql` applied and verified. It adds persistent agent runs/steps, integration metadata, verified contacts, message drafts, artifacts, schedules and finance entries.
- The eight capability domains above are the target scope. Most external actions, voice, messaging delivery, handset calls and general-purpose orchestration are not yet active.
