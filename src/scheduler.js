export function normalizeReminderSchedule(input, now = Date.now()) {
  const title = String(input?.title || "").trim();
  const message = String(input?.message || "").trim();
  const dueText = String(input?.due_at || "").trim();
  const timezone = String(input?.timezone || "UTC").trim();

  if (!title || title.length > 180) throw new Error("عنوان یادآوری باید بین ۱ تا ۱۸۰ نویسه باشد.");
  if (!message || message.length > 2000) throw new Error("متن یادآوری باید بین ۱ تا ۲۰۰۰ نویسه باشد.");
  if (!/(?:Z|[+-](?:0\d|1\d|2[0-3]):[0-5]\d)$/i.test(dueText)) {
    throw new Error("زمان باید ISO 8601 همراه با منطقه زمانی یا اختلاف UTC باشد.");
  }
  const timestamp = Date.parse(dueText);
  if (!Number.isFinite(timestamp) || timestamp <= now) throw new Error("زمان یادآوری باید معتبر و در آینده باشد.");
  if (timezone.length > 80) throw new Error("منطقه زمانی نامعتبر است.");
  let normalizedTimezone;
  try {
    normalizedTimezone = new Intl.DateTimeFormat("en-US", { timeZone: timezone }).resolvedOptions().timeZone;
  } catch {
    throw new Error("منطقه زمانی شناخته نشد.");
  }
  return {
    title,
    message,
    due_at: new Date(timestamp).toISOString(),
    timezone: normalizedTimezone
  };
}

export function isReminderDue(nextRunAt, now = Date.now()) {
  const timestamp = Date.parse(String(nextRunAt || ""));
  return Number.isFinite(timestamp) && timestamp <= now;
}

export function formatReminderText(task) {
  const title = String(task?.title || "یادآوری");
  const message = String(task?.message || "").trim();
  return message ? "⏰ یادآوری WOLF AI\n\n" + title + "\n\n" + message : "⏰ یادآوری WOLF AI\n\n" + title;
}
