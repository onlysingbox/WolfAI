-- Add optional due dates and priorities to personal tasks.
ALTER TABLE tasks ADD COLUMN due_at TEXT;
ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'normal';
CREATE INDEX IF NOT EXISTS idx_tasks_owner_due ON tasks(requested_by, due_at, status);
