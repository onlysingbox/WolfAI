# WOLF AI

Personal multilingual conversational AI agent with Telegram and web interfaces.

## Current foundation
- Cloudflare Worker API and web status dashboard
- Cloudflare D1 persistent task, project, approval, settings and audit storage
- Workers AI conversational brain using `@cf/zai-org/glm-4.7-flash`
- Natural-language chat in Telegram; slash commands are optional for normal conversation
- Conversation history stored in D1
- Response language setting: automatic (match the user's message), always English, or always Persian
- Approval-first operation model: external tools are never executed without owner approval
- Health endpoint: `/api/health`

## Runtime configuration
Bind a D1 database as `DB`, Workers AI as `AI`, and configure these Worker secrets/variables:
- Secret `BOT_TOKEN`: Telegram BotFather token for @OnlyWolfbot
- Secret `TELEGRAM_WEBHOOK_SECRET`: random webhook verification string
- Variable `ADMIN_TELEGRAM_ID`: numeric Telegram user ID of the owner
- Optional variable `BOT_USERNAME`: OnlyWolfbot
- Secret `GITHUB_TOKEN`: GitHub token for repository read access

Do not commit secrets to GitHub.

## Telegram usage
- Send an ordinary text message to chat with WOLF AI in English or Persian.
- `/language`: choose Automatic, English, or Persian response language.
- `/status`: service status
- `/tasks`: recent tasks
- `/help`: help
- Existing `/newtask`, `/newaction`, `/approve`, and `/reject` commands remain available.

## Agent tools and safety
The conversational model can propose only these read-only tools: internal health checks, GitHub repository listing, recent GitHub Actions runs, and reading a text file from an owned repository. A tool call creates an approval request and is not executed until the owner explicitly approves it. Results and failures are stored in D1 and recorded in the audit log.

Repository writes, code edits, deployments, publishing, deletion, DNS/security changes, account settings and financial actions are not enabled in this release. They must be implemented with scoped permissions, audit records, rollback planning and explicit confirmation.

## Usage and limits
Workers AI has a free daily allocation that is subject to Cloudflare's current plan and model limits. Requests may stop when the free allocation is exhausted; monitor Workers AI usage in the Cloudflare dashboard.

## Local development
Use Wrangler with the configuration in `wrangler.toml`. Apply `migrations/0001_init.sql` to the D1 database before running.
