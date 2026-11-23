# WOLF AI

Personal multilingual, single-owner AI agent. The intended product scope is a general-purpose assistant, not a language/book/research-only bot.

Full scope, acceptance criteria, voice/messaging/calling requirements and delivery order: [docs/PRODUCT_SCOPE.md](docs/PRODUCT_SCOPE.md).

## Target capability domains

1. Planning and personal operations: files, documents, email, calendars, tasks and follow-ups.
2. Infrastructure: servers, networks, VPN, DNS, security monitoring and troubleshooting.
3. Software engineering: websites, applications, bots, APIs, code review, debugging, tests and deployment.
4. Finance: budgets, calculations, expense analysis and reports. Financial transactions require explicit approval and supported integrations.
5. Automation: recurring jobs, schedules, triggers and service integrations.
6. Everyday services: travel, shopping, service comparisons and planning.
7. Content production: text, images, video, design and channel management.
8. Research and learning: web research, education, books and information analysis.

These are product targets, not a claim that every connector or action is already active. Each capability must be backed by a working integration/tool and verified before WOLF AI claims completion.

## Current deployed foundation

- Cloudflare Worker API and web status dashboard
- Cloudflare D1 persistent task, project, approval, settings and audit storage
- Multi-model Workers AI brain: GLM-4.7-Flash for everyday chat and Gemma 4 26B for complex requests
- Automatic model routing with fallback to GLM if Gemma is unavailable
- Private single-owner mode: only `ADMIN_TELEGRAM_ID` can use the bot, and only in a private Telegram chat
- Natural-language Telegram chat; slash commands are optional for normal conversation
- Conversation history stored in D1
- Owner-approved private long-term memory stored in D1, injected into relevant future conversations, searchable through natural language and `/memory`
- Response language setting: automatic, always English, or always Persian
- Approval-first operation model for external actions
- Health endpoint: `/api/health`

## Agent platform data layer

Migration `migrations/0002_agent_platform.sql` adds durable storage for:
- Multi-step agent runs and individual steps
- Connected-service integration metadata and status
- Verified contacts and recipient identifiers
- Message drafts, approval state and delivery receipts
- Generated artifacts and document metadata
- Scheduled tasks
- Finance entries for budgeting and reporting

The schema is deployed to the existing `wolfai_db` D1 database. These tables provide the foundation; they do not imply that the corresponding external integrations or execution tools are already enabled.

## Runtime configuration

Bind D1 as `DB`, Workers AI as `AI`, and configure:
- Secret `BOT_TOKEN`: Telegram BotFather token for @OnlyWolfbot
- Secret `TELEGRAM_WEBHOOK_SECRET`: random webhook verification string
- Variable `ADMIN_TELEGRAM_ID`: numeric Telegram user ID of the owner
- Variable `BOT_USERNAME`: OnlyWolfbot
- Secret `GITHUB_TOKEN`: GitHub token

Do not commit secrets to GitHub or send them in chat.

## Telegram usage

- Send ordinary messages in Persian or English.
- `/language`: choose Automatic, English, or Persian response language.
- `/model`: choose Automatic routing, GLM only, or Gemma only.
- `/status`: service status.
- `/tasks`: recent tasks.
- `/help`: help.

## Current execution limits

The active tools include GitHub repository listing, recent Actions runs, text-file reading, approval-gated creation/replacement of repository text files, private document creation/search in D1, explicit owner-approved long-term memory save/search, and owner-scoped finance ledger recording/reporting. GitHub writes are limited to one explicit repository/path per tool call, require an owner approval showing the intended content preview, and use the GitHub Contents API. Updates capture the previous file contents and SHA before approval and use the expected SHA during the write to prevent overwriting concurrent changes. The stored snapshot is not yet an automated rollback button. The agent can persist an approved run and execute up to five model-selected tool steps sequentially; each step and run status is stored in D1. Other repository operations such as deletion, branch protection, secrets, and deployment are not enabled. Email, messaging delivery, DNS/security changes, server administration and financial transactions are not yet enabled in the Worker.

Voice input, speech output and voice conversations are not yet enabled. The planned messaging flow is: transcribe the owner's voice, identify a verified recipient, prepare a draft, show the exact recipient and message, wait for explicit approval, send through a connected service, and report the delivery result.

## Usage and limits

Workers AI has plan-specific quotas and model availability. Check the Cloudflare dashboard for current usage and pricing; do not assume that every model or modality is free or unlimited.

## Local development

Use Wrangler with `wrangler.toml`. Apply `migrations/0001_init.sql` and `migrations/0002_agent_platform.sql` to a fresh D1 database before running.
