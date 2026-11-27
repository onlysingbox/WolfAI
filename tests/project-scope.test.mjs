import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";

test("project owner migration backfills ownership from linked tasks", () => {
  const db = new DatabaseSync(":memory:");
  try {
    db.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
    db.exec(readFileSync(new URL("../migrations/0002_agent_platform.sql", import.meta.url), "utf8"));
    db.prepare("INSERT INTO users (telegram_id, role) VALUES (?, ?)").run("owner-A", "owner");
    db.prepare("INSERT INTO projects (id, name) VALUES (?, ?)").run("project-A", "Private project");
    db.prepare("INSERT INTO projects (id, name) VALUES (?, ?)").run("project-orphan", "Unlinked legacy project");
    db.prepare("INSERT INTO tasks (id, title, project_id, requested_by) VALUES (?, ?, ?, ?)").run("task-A", "Task", "project-A", "owner-A");
    db.exec(readFileSync(new URL("../migrations/0003_project_owner_scope.sql", import.meta.url), "utf8"));
    const project = db.prepare("SELECT owner_id FROM projects WHERE id=?").get("project-A");
    assert.equal(project.owner_id, "owner-A");
    assert.equal(db.prepare("SELECT id FROM projects WHERE owner_id=?").all("owner-A").length, 1);
    assert.equal(db.prepare("SELECT id FROM projects WHERE owner_id=?").all("owner-B").length, 0);
    assert.equal(db.prepare("SELECT id FROM projects WHERE owner_id=?").all("owner-A").some(row => row.id === "project-orphan"), false);
  } finally {
    db.close();
  }
});

test("new projects can be stored and listed by their owner", () => {
  const db = new DatabaseSync(":memory:");
  try {
    db.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
    db.exec(readFileSync(new URL("../migrations/0002_agent_platform.sql", import.meta.url), "utf8"));
    db.exec(readFileSync(new URL("../migrations/0003_project_owner_scope.sql", import.meta.url), "utf8"));
    db.prepare("INSERT INTO projects (id, owner_id, name, status) VALUES (?, ?, ?, ?)").run("project-A", "owner-A", "A", "active");
    db.prepare("INSERT INTO projects (id, owner_id, name, status) VALUES (?, ?, ?, ?)").run("project-B", "owner-B", "B", "active");
    const own = db.prepare("SELECT id FROM projects WHERE owner_id=? ORDER BY updated_at DESC LIMIT 100").all("owner-A");
    assert.deepEqual(own.map(row => row.id), ["project-A"]);
  } finally {
    db.close();
  }
});
