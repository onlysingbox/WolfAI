import test from "node:test";
import assert from "node:assert/strict";
import { normalizeReminderSchedule, isReminderDue, formatReminderText, nextReminderRunAt, normalizeTaskDueAt } from "../src/scheduler.js";

test("normalizes a future reminder and timezone", () => {
  const result = normalizeReminderSchedule({
    title: "Doctor appointment",
    message: "Bring the documents",
    due_at: "2030-05-01T10:30:00+02:00",
    timezone: "Europe/Berlin"
  }, Date.parse("2030-01-01T00:00:00Z"));
  assert.equal(result.title, "Doctor appointment");
  assert.equal(result.message, "Bring the documents");
  assert.equal(result.due_at, "2030-05-01T08:30:00.000Z");
  assert.equal(result.timezone, "Europe/Berlin");
});

test("rejects reminders without an explicit timezone", () => {
  assert.throws(() => normalizeReminderSchedule({
    title: "Test", message: "Test", due_at: "2030-05-01T10:30:00"
  }, Date.parse("2030-01-01T00:00:00Z")));
});

test("rejects past reminders", () => {
  assert.throws(() => normalizeReminderSchedule({
    title: "Test", message: "Test", due_at: "2020-05-01T10:30:00Z"
  }, Date.parse("2030-01-01T00:00:00Z")));
});

test("checks due time safely", () => {
  assert.equal(isReminderDue("2030-01-01T10:00:00Z", Date.parse("2030-01-01T10:00:00Z")), true);
  assert.equal(isReminderDue("2030-01-01T10:01:00Z", Date.parse("2030-01-01T10:00:00Z")), false);
  assert.equal(isReminderDue("not-a-date", Date.parse("2030-01-01T10:00:00Z")), false);
});

test("formats a reminder without exposing internal data", () => {
  assert.equal(formatReminderText({ title: "Pay rent", message: "Before noon" }), "⏰ یادآوری WOLF AI\n\nPay rent\n\nBefore noon");
});

import { validateCompanionAction } from "../src/worker.js";

test("allows a future reminder only in the scheduling capability", () => {
  const action = validateCompanionAction("scheduling", {
    type: "schedule.create",
    args: {
      title: "Pay rent",
      message: "Before noon",
      due_at: "2030-05-01T10:30:00+02:00",
      timezone: "Europe/Berlin"
    }
  });
  assert.equal(action.type, "schedule.create");
  assert.equal(action.args.due_at, "2030-05-01T08:30:00.000Z");
});

test("rejects reminder creation under an unrelated capability", () => {
  assert.throws(() => validateCompanionAction("finance", {
    type: "schedule.create",
    args: {
      title: "Pay rent",
      message: "Before noon",
      due_at: "2030-05-01T10:30:00+02:00",
      timezone: "Europe/Berlin"
    }
  }));
});

test("rejects unexpected reminder fields", () => {
  assert.throws(() => validateCompanionAction("scheduling", {
    type: "schedule.create",
    args: {
      title: "Pay rent",
      message: "Before noon",
      due_at: "2030-05-01T10:30:00+02:00",
      timezone: "Europe/Berlin",
      execute_now: true
    }
  }));
});

test("allows a personal task only under personal_operations", () => {
  const action = validateCompanionAction("personal_operations", {
    type: "task.create",
    args: { title: "Review project", description: "Check milestones" }
  });
  assert.equal(action.type, "task.create");
  assert.equal(action.args.title, "Review project");
  assert.equal(action.args.project_id, null);
});

test("rejects unsafe task status values", () => {
  assert.throws(() => validateCompanionAction("personal_operations", {
    type: "task.update",
    args: { id: "00000000-0000-4000-8000-000000000001", status: "delete_everything" }
  }));
});

test("rejects project writes under a different capability", () => {
  assert.throws(() => validateCompanionAction("finance", {
    type: "project.create",
    args: { name: "Personal project", description: "" }
  }));
});

test("allows reminder listing as read-only scheduling action", () => {
  const action = validateCompanionAction("scheduling", { type: "schedule.list", args: {} });
  assert.equal(action.type, "schedule.list");
});

test("validates reminder cancellation by exact UUID", () => {
  const action = validateCompanionAction("scheduling", {
    type: "schedule.cancel",
    args: { id: "00000000-0000-4000-8000-000000000001" }
  });
  assert.equal(action.args.id, "00000000-0000-4000-8000-000000000001");
});

test("rejects invalid ISO 8601 UTC offsets", () => {
  assert.throws(() => normalizeReminderSchedule({
    title: "Test",
    message: "Test",
    due_at: "2030-05-01T10:30:00+15:00"
  }, Date.parse("2030-01-01T00:00:00Z")));
});

test("allows project and task listing as read-only personal operations", () => {
  assert.equal(validateCompanionAction("personal_operations", { type: "project.list", args: {} }).type, "project.list");
  assert.equal(validateCompanionAction("personal_operations", { type: "task.list", args: { status: "open" } }).args.status, "open");
});

test("normalizes recurring reminder intervals", () => {
  const result = normalizeReminderSchedule({
    title: "Daily check", message: "Review tasks",
    due_at: "2030-05-01T09:00:00-04:00", timezone: "America/New_York", repeat: "daily"
  }, Date.parse("2030-01-01T00:00:00Z"));
  assert.equal(result.repeat, "daily");
});

test("rejects unsupported reminder repeat intervals", () => {
  assert.throws(() => normalizeReminderSchedule({
    title: "Bad", message: "Bad", due_at: "2030-05-01T10:00:00Z", repeat: "hourly"
  }, Date.parse("2030-01-01T00:00:00Z")));
});

test("calculates the next daily reminder in its local timezone across DST", () => {
  const next = nextReminderRunAt("daily", "2030-03-09T14:00:00.000Z", "America/New_York", Date.parse("2030-03-09T14:00:00.000Z"));
  assert.equal(next, "2030-03-10T13:00:00.000Z");
});

test("calculates monthly reminders without overflowing short months", () => {
  const next = nextReminderRunAt("monthly", "2030-01-31T10:00:00.000Z", "UTC", Date.parse("2030-01-31T10:00:00.000Z"));
  assert.equal(next, "2030-02-28T10:00:00.000Z");
});

test("one-time reminders do not get rescheduled", () => {
  assert.equal(nextReminderRunAt("once", "2030-01-01T10:00:00Z", "UTC"), null);
});

test("normalizes task deadlines with an explicit UTC offset", () => {
  assert.equal(normalizeTaskDueAt("2030-05-01T10:30:00+02:00"), "2030-05-01T08:30:00.000Z");
  assert.equal(normalizeTaskDueAt(undefined), null);
});

test("rejects task deadlines without an explicit timezone", () => {
  assert.throws(() => normalizeTaskDueAt("2030-05-01T10:30:00"));
});

test("validates task priority and deadline in owner-approved actions", () => {
  const action = validateCompanionAction("personal_operations", {
    type: "task.create",
    args: { title: "Submit report", due_at: "2030-05-01T10:30:00Z", priority: "urgent" }
  });
  assert.equal(action.args.due_at, "2030-05-01T10:30:00.000Z");
  assert.equal(action.args.priority, "urgent");
  assert.throws(() => validateCompanionAction("personal_operations", {
    type: "task.create", args: { title: "Bad", priority: "critical" }
  }));
});

test("allows updating a task deadline and priority without changing status", () => {
  const action = validateCompanionAction("personal_operations", {
    type: "task.update",
    args: {
      id: "00000000-0000-4000-8000-000000000001",
      due_at: "2030-06-01T12:00:00+03:30",
      priority: "high"
    }
  });
  assert.equal(action.args.due_at, "2030-06-01T08:30:00.000Z");
  assert.equal(action.args.priority, "high");
  assert.equal(action.args.status, undefined);
});

test("rejects task updates that contain no actual changes", () => {
  assert.throws(() => validateCompanionAction("personal_operations", {
    type: "task.update",
    args: { id: "00000000-0000-4000-8000-000000000001" }
  }));
});
