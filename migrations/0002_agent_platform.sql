PRAGMA foreign_keys = ON;

-- Durable multi-step agent runtime
CREATE TABLE IF NOT EXISTS agent_runs (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  request_text TEXT NOT NULL,
  input_mode TEXT NOT NULL DEFAULT 'text',
  status TEXT NOT NULL DEFAULT 'planning',
  plan_json TEXT,
  result_json TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_steps (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  step_no INTEGER NOT NULL,
  capability TEXT NOT NULL,
  title TEXT NOT NULL,
  input_json TEXT,
  output_json TEXT,
  status TEXT NOT NULL DEFAULT 'queued',
  approval_required INTEGER NOT NULL DEFAULT 1,
  approved_by TEXT,
  approved_at TEXT,
  started_at TEXT,
  completed_at TEXT,
  error_text TEXT,
  FOREIGN KEY(run_id) REFERENCES agent_runs(id) ON DELETE CASCADE,
  UNIQUE(run_id, step_no)
);

CREATE TABLE IF NOT EXISTS integrations (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  display_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'not_connected',
  scopes_json TEXT,
  account_label TEXT,
  metadata_json TEXT,
  connected_at TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(provider, account_label)
);

CREATE TABLE IF NOT EXISTS contacts (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  service TEXT NOT NULL,
  external_id TEXT,
  username TEXT,
  address TEXT,
  verified_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS message_drafts (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  service TEXT NOT NULL,
  recipient_contact_id TEXT,
  recipient_label TEXT NOT NULL,
  subject TEXT,
  body TEXT NOT NULL,
  attachment_refs_json TEXT,
  status TEXT NOT NULL DEFAULT 'draft',
  approved_at TEXT,
  sent_at TEXT,
  external_message_id TEXT,
  error_text TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(recipient_contact_id) REFERENCES contacts(id)
);

CREATE TABLE IF NOT EXISTS artifacts (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  mime_type TEXT,
  storage_provider TEXT,
  storage_key TEXT,
  content_text TEXT,
  metadata_json TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS schedules (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  title TEXT NOT NULL,
  schedule_spec TEXT NOT NULL,
  timezone TEXT NOT NULL DEFAULT 'UTC',
  task_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  next_run_at TEXT,
  last_run_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS finance_entries (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  entry_type TEXT NOT NULL,
  category TEXT NOT NULL,
  amount_minor INTEGER NOT NULL,
  currency TEXT NOT NULL,
  description TEXT,
  occurred_at TEXT NOT NULL,
  metadata_json TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_runs_owner_created ON agent_runs(owner_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_steps_run_status ON agent_steps(run_id, status);
CREATE INDEX IF NOT EXISTS idx_message_drafts_owner_status ON message_drafts(owner_id, status);
CREATE INDEX IF NOT EXISTS idx_schedules_status_next_run ON schedules(status, next_run_at);
CREATE INDEX IF NOT EXISTS idx_finance_entries_owner_date ON finance_entries(owner_id, occurred_at);
