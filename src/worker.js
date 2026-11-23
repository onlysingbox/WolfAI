const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
});

const html = () => new Response(`<!doctype html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>WOLF AI</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#0b1220;color:#edf3ff;font-family:Tahoma,Arial,sans-serif}
main{max-width:900px;margin:40px auto;padding:24px}.hero{padding:28px;border:1px solid #263b5b;border-radius:20px;background:linear-gradient(135deg,#142c4c,#102033)}
h1{margin:0 0 8px;font-size:34px}.muted{color:#a9bad3}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:14px;margin-top:18px}
.card{padding:18px;border:1px solid #263b5b;border-radius:14px;background:#111d30}.value{font-size:20px;font-weight:bold;margin-top:10px;color:#8bd7b1}
.notice{margin-top:20px;padding:16px;border-radius:12px;background:#192b43;color:#c8d8ee;line-height:1.9}
code{color:#9bd7ff}a{color:#9bd7ff}
</style></head><body><main>
<section class="hero"><h1>🐺 WOLF AI</h1><div class="muted">مرکز فرماندهی دستیار شخصی</div><p>هسته اولیه فعال است. اتصال تلگرام پس از تنظیم امن توکن و شناسه مالک فعال می‌شود.</p></section>
<section class="grid">
<div class="card">وضعیت Worker<div class="value">فعال</div></div>
<div class="card">حافظه D1<div class="value">متصل</div></div>
<div class="card">حالت عملیات<div class="value">نیازمند تأیید</div></div>
<div class="card">ربات<div class="value">@OnlyWolfbot</div></div>
</section>
<div class="notice"><b>مرحله بعد:</b><br>در Cloudflare برای Worker با نام <code>wolfai</code>، سه Secret با نام‌های <code>BOT_TOKEN</code>، <code>TELEGRAM_WEBHOOK_SECRET</code> و <code>ADMIN_TELEGRAM_ID</code> تنظیم می‌شوند. مقادیر محرمانه را در GitHub یا پیام چت قرار نده.</div>
</main></body></html>`, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });

async function log(env, actor, action, targetType = null, targetId = null, details = null) {
  await env.DB.prepare("INSERT INTO activity_logs (actor, action, target_type, target_id, details) VALUES (?, ?, ?, ?, ?)")
    .bind(String(actor), action, targetType, targetId, details ? JSON.stringify(details) : null).run();
}

async function telegram(env, method, payload) {
  if (!env.BOT_TOKEN) throw new Error("BOT_TOKEN is not configured");
  const response = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload)
  });
  const data = await response.json();
  if (!data.ok) throw new Error(data.description || "Telegram API error");
  return data.result;
}

async function reply(env, chatId, text, extra = {}) {
  return telegram(env, "sendMessage", { chat_id: chatId, text, ...extra });
}

async function createTask(env, title, ownerId) {
  const id = crypto.randomUUID().slice(0, 8);
  await env.DB.prepare("INSERT INTO tasks (id, title, status, risk_level, requested_by) VALUES (?, ?, 'pending_approval', 'low', ?)")
    .bind(id, title.slice(0, 240), String(ownerId)).run();
  await env.DB.prepare("INSERT INTO approvals (id, task_id, action_summary, risk_level, status) VALUES (?, ?, ?, 'low', 'pending')")
    .bind(crypto.randomUUID(), id, `بررسی و برنامه‌ریزی وظیفه: ${title.slice(0, 240)}`).run();
  await log(env, ownerId, "task.created", "task", id, { title });
  return id;
}

async function decideTask(env, id, ownerId, decision) {
  const task = await env.DB.prepare("SELECT id, title, status FROM tasks WHERE id = ?").bind(id).first();
  if (!task) return { error: "وظیفه پیدا نشد." };
  if (task.status !== "pending_approval") return { error: "این وظیفه قبلاً تعیین تکلیف شده است." };
  const status = decision === "approve" ? "approved" : "rejected";
  await env.DB.prepare("UPDATE tasks SET status = ?, approved_by = ?, approved_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
    .bind(status, String(ownerId), id).run();
  await env.DB.prepare("UPDATE approvals SET status = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP WHERE task_id = ? AND status = 'pending'")
    .bind(status, String(ownerId), id).run();
  await log(env, ownerId, `task.${status}`, "task", id, { title: task.title });
  return { task, status };
}

async function handleTelegramUpdate(request, env) {
  if (!env.BOT_TOKEN || !env.TELEGRAM_WEBHOOK_SECRET || !env.ADMIN_TELEGRAM_ID) {
    return json({ ok: false, error: "Telegram setup incomplete" }, 503);
  }
  if (request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.TELEGRAM_WEBHOOK_SECRET) {
    return json({ ok: false, error: "Unauthorized" }, 401);
  }
  const update = await request.json();
  const message = update.message;
  const callback = update.callback_query;
  if (callback) {
    const userId = String(callback.from?.id || "");
    if (userId !== String(env.ADMIN_TELEGRAM_ID)) {
      await telegram(env, "answerCallbackQuery", { callback_query_id: callback.id, text: "دسترسی غیرمجاز" });
      return json({ ok: true });
    }
    const [decision, id] = String(callback.data || "").split(":");
    if (!["approve", "reject"].includes(decision) || !id) return json({ ok: true });
    const result = await decideTask(env, id, userId, decision);
    const label = result.error || (decision === "approve" ? "طرح وظیفه تأیید شد؛ اجرای خارجی هنوز فعال نیست." : "وظیفه رد شد.");
    await telegram(env, "answerCallbackQuery", { callback_query_id: callback.id, text: label });
    await telegram(env, "editMessageReplyMarkup", { chat_id: callback.message.chat.id, message_id: callback.message.message_id, reply_markup: { inline_keyboard: [] } });
    await reply(env, callback.message.chat.id, label);
    return json({ ok: true });
  }
  if (!message?.text) return json({ ok: true });
  const userId = String(message.from?.id || "");
  const chatId = message.chat.id;
  if (userId !== String(env.ADMIN_TELEGRAM_ID)) {
    await reply(env, chatId, "این ربات خصوصی است و فقط مالک تعیین‌شده اجازه استفاده دارد.");
    return json({ ok: true });
  }
  await env.DB.prepare("INSERT INTO users (telegram_id, username, first_name, role, last_seen_at) VALUES (?, ?, ?, 'owner', CURRENT_TIMESTAMP) ON CONFLICT(telegram_id) DO UPDATE SET username=excluded.username, first_name=excluded.first_name, last_seen_at=CURRENT_TIMESTAMP")
    .bind(userId, message.from.username || null, message.from.first_name || null).run();
  const text = message.text.trim();
  const [command, ...rest] = text.split(/\s+/);
  const arg = rest.join(" ");
  if (command === "/start" || command === "/help") {
    await reply(env, chatId, "🐺 WOLF AI آماده است.\n\n/status وضعیت سیستم\n/newtask عنوان کار\n/tasks فهرست وظایف\n/approve شناسه تأیید طرح\n/reject شناسه رد طرح\n\nتوجه: تأیید فعلاً فقط طرح را تأیید می‌کند؛ اجرای ابزارهای خارجی در مرحله بعد اضافه می‌شود.");
  } else if (command === "/status") {
    const count = await env.DB.prepare("SELECT COUNT(*) AS total FROM tasks").first();
    await reply(env, chatId, `WOLF AI فعال است.\nحافظه: متصل\nتعداد وظایف: ${count.total}\nحالت: تأییدمحور`);
  } else if (command === "/newtask") {
    if (!arg) return reply(env, chatId, "عنوان را بنویس: /newtask بررسی وضعیت سرور");
    const id = await createTask(env, arg, userId);
    await reply(env, chatId, `وظیفه ثبت شد. شناسه: ${id}\nبرای تأیید طرح یا رد آن از دکمه‌ها استفاده کن.\n\nفعلاً هیچ تغییری در سرویس خارجی انجام نمی‌شود.`, {
      reply_markup: { inline_keyboard: [[{ text: "تأیید طرح", callback_data: `approve:${id}` }, { text: "رد", callback_data: `reject:${id}` }]] }
    });
  } else if (command === "/tasks") {
    const rows = await env.DB.prepare("SELECT id, title, status FROM tasks ORDER BY created_at DESC LIMIT 10").all();
    const lines = (rows.results || []).map(x => `• ${x.id} | ${x.status} | ${x.title}`);
    await reply(env, chatId, lines.length ? lines.join("\n") : "هنوز وظیفه‌ای ثبت نشده است.");
  } else if (command === "/approve" || command === "/reject") {
    if (!arg) return reply(env, chatId, "شناسه وظیفه را وارد کن.");
    const result = await decideTask(env, arg, userId, command === "/approve" ? "approve" : "reject");
    await reply(env, chatId, result.error || (command === "/approve" ? "طرح تأیید شد؛ اجرای واقعی هنوز در دسترس نیست." : "وظیفه رد شد."));
  } else {
    await reply(env, chatId, "دستور شناخته نشد. /help را بزن.");
  }
  return json({ ok: true });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/api/health") {
      try {
        const row = await env.DB.prepare("SELECT COUNT(*) AS tables_ok FROM sqlite_master WHERE type='table' AND name IN ('users','projects','tasks','approvals','activity_logs','settings')").first();
        return json({ ok: row.tables_ok === 6, service: "WOLF AI", bot: "@OnlyWolfbot", database: row.tables_ok === 6 ? "connected" : "schema_incomplete", mode: "approval_required", version: "0.1.0" });
      } catch (error) {
        return json({ ok: false, service: "WOLF AI", error: error.message }, 500);
      }
    }
    if (request.method === "POST" && url.pathname === "/telegram/webhook") {
      try { return await handleTelegramUpdate(request, env); }
      catch (error) { console.error("telegram webhook error", error); return json({ ok: false, error: "Webhook processing failed" }, 500); }
    }
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/dashboard")) return html();
    if (url.pathname.startsWith("/api/")) return json({ ok: false, error: "Not implemented in this version" }, 501);
    return json({ ok: false, error: "Not found" }, 404);
  }
};