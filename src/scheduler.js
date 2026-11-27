export function normalizeReminderSchedule(input, now = Date.now()) {
  const title = String(input?.title || "").trim();
  const message = String(input?.message || "").trim();
  const dueText = String(input?.due_at || "").trim();
  const timezone = String(input?.timezone || "UTC").trim();
  const repeat = String(input?.repeat || "once").trim();

  if (!title || title.length > 180) throw new Error("عنوان یادآوری باید بین ۱ تا ۱۸۰ نویسه باشد.");
  if (!message || message.length > 2000) throw new Error("متن یادآوری باید بین ۱ تا ۲۰۰۰ نویسه باشد.");
  if (!/(?:Z|[+-](?:(?:0\d|1[0-3]):[0-5]\d|14:00))$/i.test(dueText)) {
    throw new Error("زمان باید ISO 8601 همراه با منطقه زمانی یا اختلاف UTC باشد.");
  }
  const timestamp = Date.parse(dueText);
  if (!Number.isFinite(timestamp) || timestamp <= now) throw new Error("زمان یادآوری باید معتبر و در آینده باشد.");
  if (!["once", "daily", "weekly", "monthly"].includes(repeat)) throw new Error("تکرار یادآوری معتبر نیست.");
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
    timezone: normalizedTimezone,
    repeat
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


function reminderLocalParts(timestamp, timezone) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
  }).formatToParts(new Date(timestamp));
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return {
    year: Number(values.year), month: Number(values.month), day: Number(values.day),
    hour: Number(values.hour), minute: Number(values.minute), second: Number(values.second)
  };
}

function reminderLocalToUtc(local, timezone) {
  const target = Date.UTC(local.year, local.month - 1, local.day, local.hour, local.minute, local.second);
  let guess = target;
  for (let i = 0; i < 6; i++) {
    const actual = reminderLocalParts(guess, timezone);
    const represented = Date.UTC(actual.year, actual.month - 1, actual.day, actual.hour, actual.minute, actual.second);
    const delta = target - represented;
    if (delta === 0) return guess;
    guess += delta;
  }
  return null;
}

function advanceReminderDate(local, repeat) {
  if (repeat === "monthly") {
    const year = local.month === 12 ? local.year + 1 : local.year;
    const month = local.month === 12 ? 1 : local.month + 1;
    const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
    return { ...local, year, month, day: Math.min(local.day, lastDay) };
  }
  const date = new Date(Date.UTC(local.year, local.month - 1, local.day + (repeat === "weekly" ? 7 : 1)));
  return { ...local, year: date.getUTCFullYear(), month: date.getUTCMonth() + 1, day: date.getUTCDate() };
}

export function nextReminderRunAt(repeat, previousRunAt, timezone = "UTC", now = Date.now()) {
  if (repeat === "once") return null;
  if (!["daily", "weekly", "monthly"].includes(repeat)) throw new Error("الگوی تکرار یادآوری نامعتبر است.");
  const previous = Date.parse(String(previousRunAt || ""));
  if (!Number.isFinite(previous)) throw new Error("زمان اجرای قبلی معتبر نیست.");
  let local = reminderLocalParts(previous, timezone);
  for (let attempt = 0; attempt < 500; attempt++) {
    local = advanceReminderDate(local, repeat);
    let next = reminderLocalToUtc(local, timezone);
    if (next === null) {
      const naive = Date.UTC(local.year, local.month - 1, local.day, local.hour, local.minute, local.second);
      for (let minute = 1; minute <= 180 && next === null; minute++) {
        const shifted = new Date(naive + minute * 60000);
        const candidate = {
          year: shifted.getUTCFullYear(), month: shifted.getUTCMonth() + 1, day: shifted.getUTCDate(),
          hour: shifted.getUTCHours(), minute: shifted.getUTCMinutes(), second: shifted.getUTCSeconds()
        };
        next = reminderLocalToUtc(candidate, timezone);
      }
    }
    if (next !== null && next > now) return new Date(next).toISOString();
  }
  throw new Error("محاسبه زمان بعدی یادآوری از محدوده مجاز خارج شد.");
}
