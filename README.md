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
- Variable `ADMIN_TELEGRAM_ID`: numeric Telegram user ID of the owner
- Optional variable `BOT_USERNAME`: OnlyWolfbot

Do not commit secrets to GitHub.

## Telegram commands
- `/start` and `/help`: show help
- `/status`: service status
- `/newtask <title>`: record a planning task awaiting owner approval
- `/newaction system.health`: propose a read-only system health check
- `/newaction github.list_repos`: propose listing accessible owner repositories
- `/newaction github.workflow_runs <repo>`: propose reading recent workflow runs
- `/newaction github.get_file <repo> <path>`: propose reading a repository file
- Approving a `/newaction` executes that allowlisted read-only tool and stores the result in D1
- `/tasks`: list recent tasks
- `/approve <task-id>`: approve a task plan or execute an explicitly proposed allowlisted read-only action
- `/reject <task-id>`: reject a task

## Local development
Use Wrangler with the configuration in `wrangler.toml`. Apply `migrations/0001_init.sql` to the D1 database before running.

## Tool execution and safety
The first execution engine supports only read-only tools: internal health checks, GitHub repository listing, recent GitHub Actions runs, and reading a text file from an owned repository. Each action is proposed first and runs only after the owner explicitly approves it. Results and failures are stored in D1 and recorded in the audit log. File writes, deployments, publishing, deletion, DNS/security changes, account settings and financial actions are not enabled in this stage and must require explicit confirmation when added.
