import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { claimCompanionMissionStep, getOwnedCompanionMission } from "../src/worker.js";

function makeD1() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(`
    PRAGMA foreign_keys = ON;
    CREATE TABLE agent_runs (
      id TEXT PRIMARY KEY,
      owner_id TEXT NOT NULL,
      status TEXT NOT NULL
    );
    CREATE TABLE agent_steps (
      id TEXT PRIMARY KEY,
      run_id TEXT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
      step_no INTEGER NOT NULL,
      status TEXT NOT NULL,
      approval_required INTEGER NOT NULL DEFAULT 1,
      approved_by TEXT,
      started_at TEXT,
      UNIQUE(run_id, step_no)
    );
  `);
  const DB = {
    prepare(sql) {
      return {
        bind(...args) {
          return {
            async first() { return sqlite.prepare(sql).get(...args) ?? null; },
            async run() {
              const result = sqlite.prepare(sql).run(...args);
              return { meta: { changes: Number(result.changes) } };
            }
          };
        }
      };
    }
  };
  return { DB, sqlite };
}

function seed(db, { owner = "owner-A", runStatus = "approved_pending_execution", steps = [] } = {}) {
  db.sqlite.prepare("INSERT INTO agent_runs(id,owner_id,status) VALUES(?,?,?)").run("mission-1", owner, runStatus);
  const insert = db.sqlite.prepare("INSERT INTO agent_steps(id,run_id,step_no,status,approval_required,approved_by) VALUES(?,?,?,?,?,?)");
  for (const step of steps) insert.run(step.id, "mission-1", step.no, step.status, 1, step.approvedBy ?? owner);
}

const pending = (id, no, status = "awaiting_execution_approval", approvedBy = "owner-A") =>
  ({ id, no, status, approvedBy });

test("D1-compatible SQL: only the authenticated owner can retrieve a mission", async () => {
  const db = makeD1();
  seed(db);
  assert.equal((await getOwnedCompanionMission(db, "mission-1", "owner-A")).owner_id, "owner-A");
  assert.equal(await getOwnedCompanionMission(db, "mission-1", "owner-B"), null);
  db.sqlite.close();
});

test("D1-compatible SQL: concurrent duplicate claims produce exactly one winner", async () => {
  const db = makeD1();
  seed(db, { steps: [pending("step-1", 1)] });
  const args = { stepId: "step-1", missionId: "mission-1", stepNo: 1, ownerId: "owner-A" };
  const claims = await Promise.all([
    claimCompanionMissionStep(db, args),
    claimCompanionMissionStep(db, args)
  ]);
  assert.equal(claims.filter(Boolean).length, 1);
  assert.equal(db.sqlite.prepare("SELECT status FROM agent_steps WHERE id='step-1'").get().status, "running");
  db.sqlite.close();
});

test("D1-compatible SQL: a later step cannot start before earlier steps complete", async () => {
  const db = makeD1();
  seed(db, { steps: [
    pending("step-1", 1, "approved_pending_execution"),
    pending("step-2", 2)
  ] });
  assert.equal(await claimCompanionMissionStep(db, {
    stepId: "step-2", missionId: "mission-1", stepNo: 2, ownerId: "owner-A"
  }), false);
  db.sqlite.prepare("UPDATE agent_steps SET status='completed' WHERE id='step-1'").run();
  assert.equal(await claimCompanionMissionStep(db, {
    stepId: "step-2", missionId: "mission-1", stepNo: 2, ownerId: "owner-A"
  }), true);
  db.sqlite.close();
});

test("D1-compatible SQL: wrong approver, running sibling, and stopped mission are rejected", async () => {
  const wrongOwner = makeD1();
  seed(wrongOwner, { steps: [pending("step-1", 1, "awaiting_execution_approval", "owner-B")] });
  assert.equal(await claimCompanionMissionStep(wrongOwner, {
    stepId: "step-1", missionId: "mission-1", stepNo: 1, ownerId: "owner-A"
  }), false);
  wrongOwner.sqlite.close();

  const sibling = makeD1();
  seed(sibling, { steps: [
    pending("step-1", 1, "running"),
    pending("step-2", 2)
  ] });
  assert.equal(await claimCompanionMissionStep(sibling, {
    stepId: "step-2", missionId: "mission-1", stepNo: 2, ownerId: "owner-A"
  }), false);
  sibling.sqlite.close();

  const stopped = makeD1();
  seed(stopped, { runStatus: "execution_failed", steps: [pending("step-1", 1)] });
  assert.equal(await claimCompanionMissionStep(stopped, {
    stepId: "step-1", missionId: "mission-1", stepNo: 1, ownerId: "owner-A"
  }), false);
  stopped.sqlite.close();
});
