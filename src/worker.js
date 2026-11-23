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
main{max-width:900px;margin:32px auto;padding:20px}.hero{padding:26px;border:1px solid #263b5b;border-radius:20px;background:linear-gradient(135deg,#142c4c,#102033)}
h1{margin:0 0 8px;font-size:32px}.muted{color:#a9bad3;line-height:1.8}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin-top:16px}
.card,.panel{padding:18px;border:1px solid #263b5b;border-radius:14px;background:#111d30;margin-top:14px}.value{font-size:20px;font-weight:bold;margin-top:10px;color:#8bd7b1}
button{cursor:pointer;border:0;border-radius:10px;padding:12px 18px;color:white;font-weight:bold;font-size:14px;min-height:44px}
button:disabled{opacity:.55;cursor:wait}.blue{background:#2563eb}.green{background:#16845b}
input{display:block;width:100%;margin:10px 0 12px;padding:13px;border-radius:10px;border:1px solid #3b5272;background:#0b1627;color:#fff;font-size:15px}
.result{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;margin-top:12px;color:#c8d8ee}.hint{font-size:13px;color:#9fb2cc;line-height:1.8}
</style></head><body><main>
<section class="hero"><h1>🐺 WOLF AI</h1><div class="muted">مرکز فرماندهی دستیار شخصی</div><p>هسته اولیه فعال است. همه عملیات حساس با تأیید مالک انجام می‌شوند.</p></section>
<section class="grid">
<div class="card">وضعیت Worker<div class="value">فعال</div></div>
<div class="card">حافظه D1<div class="value">متصل</div></div>
<div class="card">حالت عملیات<div class="value">نیازمند تأیید</div></div>
<div class="card">ربات<div class="value">@OnlyWolfbot</div></div>
</section>
<section class="panel">
<h3>اتصال ربات تلگرام</h3>
<p class="hint">کلید وب‌هوک را در این قسمت وارد کن. این کلید همان Secret با نام TELEGRAM_WEBHOOK_SECRET است. آن را در چت یا GitHub ارسال نکن.</p>
<input id="telegramKey" type="password" autocomplete="off" placeholder="کلید اتصال تلگرام">
<button id="connect" class="blue" type="button">اتصال ربات تلگرام</button>
<div id="telegramResult" class="result" role="status"></div>
</section>
<section class="panel">
<h3>اتصال فقط‌خواندنی GitHub</h3>
<p class="hint">فهرست مخازن و آخرین اجرای GitHub Actions. دسترسی فقط‌خواندنی است.</p>
<input id="githubKey" type="password" autocomplete="off" placeholder="کلید دسترسی پنل (DASHBOARD_ACCESS_KEY)">
<button id="github" class="green" type="button">بررسی مخازن GitHub</button>
<div id="githubResult" class="result" role="status"></div>
</section>
<script>
(() => {
 const byId = id => document.getElementById(id);
 const connect = byId("connect"), github = byId("github");
 connect.addEventListener("click", async () => {
   const key = byId("telegramKey").value.trim(), out = byId("telegramResult");
   if (!key) { out.textContent = "ابتدا کلید اتصال تلگرام را وارد کن."; return; }
   connect.disabled = true; out.textContent = "در حال ثبت اتصال...";
   try {
     const r = await fetch("/api/setup-webhook", {method:"POST", headers:{"x-wolf-setup-key":key}});
     const d = await r.json();
     out.textContent = r.ok && d.ok ? "اتصال وب‌هوک با موفقیت ثبت شد." : "خطا: " + (d.error || ("HTTP " + r.status));
   } catch (e) { out.textContent = "خطا در ارتباط با سرور. اتصال اینترنت را بررسی کن."; }
   finally { connect.disabled = false; }
 });
 github.addEventListener("click", async () => {
   const key = byId("githubKey").value.trim(), out = byId("githubResult");
   if (!key) { out.textContent = "ابتدا کلید دسترسی پنل را وارد کن."; return; }
   github.disabled = true; out.textContent = "در حال دریافت فهرست مخازن...";
   try {
     const r = await fetch("/api/github/overview", {headers:{"x-wolf-dashboard-key":key}});
     const d = await r.json();
     if (!r.ok || !d.ok) { out.textContent = "خطا: " + (d.error || ("HTTP " + r.status)); return; }
     out.textContent = (d.repositories || []).map(x => (x.private ? "🔒 " : "🌐 ") + x.name + " | شاخه: " + x.default_branch + " | بروزرسانی: " + x.updated_at + "\n" + x.html_url).join("\\n\\n") || "مخزنی پیدا نشد.";
   } catch (e) { out.textContent = "خطا در ارتباط با سرور. اتصال اینترنت را بررسی کن."; }
   finally { github.disabled = false; }
 });
})();
</script>
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


async function githubRequest(env, path) {
  if (!env.GITHUB_TOKEN) throw new Error("GITHUB_TOKEN is not configured");
  const response = await fetch("https://api.github.com" + path, {
    headers: {
      "accept": "application/vnd.github+json",
      "authorization": "Bearer " + env.GITHUB_TOKEN,
      "x-github-api-version": "2022-11-28",
      "user-agent": "WOLF-AI"
    }
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || "GitHub API error");
  return data;
}

function dashboardAuthorized(request, env) {
  const supplied = request.headers.get("x-wolf-dashboard-key") || "";
  return !!env.DASHBOARD_ACCESS_KEY && supplied.length === env.DASHBOARD_ACCESS_KEY.length &&
    [...supplied].reduce((n, c, i) => n | (c.charCodeAt(0) ^ env.DASHBOARD_ACCESS_KEY.charCodeAt(i)), 0) === 0;
}

async function githubOverview(request, env, url) {
  if (!dashboardAuthorized(request, env)) return json({ ok: false, error: "دسترسی پنل معتبر نیست" }, 401);
  try {
    const repo = url.searchParams.get("repo");
    if (repo) {
      if (!/^[A-Za-z0-9_.-]{1,100}$/.test(repo)) return json({ ok: false, error: "نام مخزن نامعتبر است" }, 400);
      const data = await githubRequest(env, "/repos/onlysingbox/" + encodeURIComponent(repo) + "/actions/runs?per_page=5");
      return json({ ok: true, type: "workflow_runs", repository: repo, runs: (data.workflow_runs || []).map(x => ({
        name: x.name, event: x.event, status: x.status, conclusion: x.conclusion,
        branch: x.head_branch, created_at: x.created_at, updated_at: x.updated_at, html_url: x.html_url
      })) });
    }
    const repos = await githubRequest(env, "/user/repos?visibility=all&affiliation=owner&sort=updated&per_page=100");
    return json({ ok: true, type: "repositories", repositories: repos
      .filter(x => x.owner && x.owner.login.toLowerCase() === "onlysingbox")
      .map(x => ({ name: x.name, private: x.private, description: x.description,
        default_branch: x.default_branch, updated_at: x.updated_at, html_url: x.html_url }))
      .slice(0, 50) });
  } catch (error) {
    console.error("github readonly request failed", error);
    return json({ ok: false, error: error.message }, 502);
  }
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
    if (request.method === "GET" && url.pathname === "/api/github/overview") {
      return await githubOverview(request, env, url);
    }
    if (request.method === "GET" && url.pathname === "/api/health") {
      try {
        const row = await env.DB.prepare("SELECT COUNT(*) AS tables_ok FROM sqlite_master WHERE type='table' AND name IN ('users','projects','tasks','approvals','activity_logs','settings')").first();
        return json({ ok: row.tables_ok === 6, service: "WOLF AI", bot: "@OnlyWolfbot", database: row.tables_ok === 6 ? "connected" : "schema_incomplete", mode: "approval_required", github_readonly: !!env.GITHUB_TOKEN, version: "0.2.0" });
      } catch (error) {
        return json({ ok: false, service: "WOLF AI", error: error.message }, 500);
      }
    }
    if (request.method === "POST" && url.pathname === "/api/setup-webhook") {
      if (!env.TELEGRAM_WEBHOOK_SECRET || request.headers.get("x-wolf-setup-key") !== env.TELEGRAM_WEBHOOK_SECRET) {
        return json({ ok: false, error: "کلید اتصال نامعتبر است" }, 401);
      }
      if (!env.BOT_TOKEN || !env.ADMIN_TELEGRAM_ID) {
        return json({ ok: false, error: "ابتدا BOT_TOKEN و ADMIN_TELEGRAM_ID را در تنظیمات Worker وارد کن" }, 503);
      }
      try {
        const webhookUrl = new URL("/telegram/webhook", url.origin).toString();
        const result = await telegram(env, "setWebhook", {
          url: webhookUrl,
          secret_token: env.TELEGRAM_WEBHOOK_SECRET,
          allowed_updates: ["message", "callback_query"],
          drop_pending_updates: false
        });
        return json({ ok: true, webhook: result, url: webhookUrl });
      } catch (error) {
        return json({ ok: false, error: error.message }, 502);
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