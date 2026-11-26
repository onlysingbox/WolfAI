import test from "node:test";
import assert from "node:assert/strict";
import { normalizeReminderSchedule, isReminderDue, formatReminderText } from "../src/scheduler.js";

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
