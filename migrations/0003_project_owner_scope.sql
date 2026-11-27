-- Scope personal projects to the single verified WOLF owner.
-- Existing projects are backfilled from their linked tasks when possible.
ALTER TABLE projects ADD COLUMN owner_id TEXT;

UPDATE projects
SET owner_id = (
  SELECT tasks.requested_by
  FROM tasks
  WHERE tasks.project_id = projects.id
    AND tasks.requested_by IS NOT NULL
  ORDER BY tasks.created_at ASC
  LIMIT 1
)
WHERE owner_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_projects_owner_updated
ON projects(owner_id, updated_at);
