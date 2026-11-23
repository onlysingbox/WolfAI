# WOLF AI

Personal multi-purpose AI agent with Telegram and web interfaces.

## Current foundation
- Cloudflare Worker API and web status dashboard
- Cloudflare D1 persistent task, project, approval and audit storage
- Telegram webhook handler for the owner bot
- Approval-first operation model: no external action is executed automatically
- Health endpoint: `/api/health`

## Runtime configuration
Bind a D1 database as `DB` and configure these Worker secrets/variables:
- Secret `BOT_TOKEN`: Telegram BotFather token for @OnlyWolfbot
- Secret `TELEGRAM_WEBHOOK_SECRET`: random webhook verification string
- Secret `ADMIN_TELEGRAM_ID`: numeric Telegram user ID of the owner
- Optional variable `BOT_USERNAME`: OnlyWolfbot

Do not commit secrets to GitHub.

## Telegram commands
- `/start` and `/help`: show help
- `/status`: service status
- `/newtask <title>`: create a task awaiting owner approval
- `/tasks`: list recent tasks
- `/approve <task-id>`: approve a task plan (does not yet execute external actions)
- `/reject <task-id>`: reject a task

## Local development
Use Wrangler with the configuration in `wrangler.toml`. Apply `migrations/0001_init.sql` to the D1 database before running.

## Safety
WOLF AI is configured for approval-first operation. Approval records are auditable. Destructive actions, publishing, account changes and financial operations must require explicit confirmation.
