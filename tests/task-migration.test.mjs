import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";

test("task deadline and priority migration adds defaults safely", () => {
  const db = new DatabaseSync(":memory:");
  try {
    db.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
    db.exec(readFileSync(new URL("../migrations/0002_agent_platform.sql", import.meta.url), "utf8"));
    db.exec(readFileSync(new URL("../migrations/0003_project_owner_scope.sql", import.meta.url), "utf8"));
    db.prepare("INSERT INTO users (telegram_id, role) VALUES (?, ?)").run("owner-A", "owner");
    db.prepare("INSERT INTO tasks (id,title,requested_by) VALUES (?,?,?)").run("task-old", "Existing task", "owner-A");
    db.exec(readFileSync(new URL("../migrations/0004_task_due_priority.sql", import.meta.url), "utf8"));
    const existing = db.prepare("SELECT due_at,priority FROM tasks WHERE id=?").get("task-old");
    assert.equal(existing.due_at, null);
    assert.equal(existing.priority, "normal");
    db.prepare("INSERT INTO tasks (id,title,requested_by,due_at,priority) VALUES (?,?,?,?,?)")
      .run("task-new", "New task", "owner-A", "2030-05-01T10:00:00.000Z", "urgent");
    const created = db.prepare("SELECT due_at,priority FROM tasks WHERE id=?").get("task-new");
    assert.equal(created.priority, "urgent");
  } finally {
    db.close();
  }
});
