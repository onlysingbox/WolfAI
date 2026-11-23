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
<form method="POST" action="/dashboard/connect">
<input name="key" type="password" autocomplete="off" required placeholder="کلید اتصال تلگرام">
<button class="blue" type="submit">اتصال ربات تلگرام</button>
</form>
</section>
<section class="panel">
<h3>اتصال فقط‌خواندنی GitHub</h3>
<p class="hint">فهرست مخازن و آخرین اجرای GitHub Actions. دسترسی فقط‌خواندنی است.</p>
<form method="POST" action="/dashboard/github">
<input name="key" type="password" autocomplete="off" required placeholder="کلید دسترسی پنل (DASHBOARD_ACCESS_KEY)">
<button class="green" type="submit">بررسی مخازن GitHub</button>
</form>
</section>

</main></body></html>`, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });


function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, c => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;" }[c]));
}
function resultPage(message, success = false, extra = "") {
  return new Response(`<!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>WOLF AI</title><body style="margin:0;padding:28px;background:#0b1220;color:#edf3ff;font:16px Tahoma,Arial;line-height:2"><main style="max-width:760px;margin:auto;background:#111d30;border:1px solid #263b5b;border-radius:18px;padding:24px"><h2>${success ? "✅ نتیجه عملیات" : "⚠️ نتیجه عملیات"}</h2><p>${escapeHtml(message)}</p>${extra}<p><a style="color:#8bd7b1" href="/">بازگشت به داشبورد WOLF AI</a></p></main></body></html>`, {headers:{"content-type":"text/html; charset=utf-8","cache-control":"no-store"}});
}
async function handleDashboardPost(request, env, path) {
  let form;
  try { form = await request.formData(); } catch { return resultPage("اطلاعات فرم خوانده نشد."); }
  const key = String(form.get("key") || "");
  if (!key) return resultPage("کلید وارد نشده است.");
  if (path === "/dashboard/connect") {
    if (!env.TELEGRAM_WEBHOOK_SECRET || key !== env.TELEGRAM_WEBHOOK_SECRET) return resultPage("کلید اتصال تلگرام معتبر نیست.");
    if (!env.BOT_TOKEN || !env.ADMIN_TELEGRAM_ID) return resultPage("تنظیمات Worker ناقص است. باید BOT_TOKEN و ADMIN_TELEGRAM_ID در بخش Variables and Secrets ثبت شوند.");
    try {
      const webhookUrl = new URL("/telegram/webhook", new URL(request.url).origin).toString();
      const result = await telegram(env, "setWebhook", {url:webhookUrl,secret_token:env.TELEGRAM_WEBHOOK_SECRET,allowed_updates:["message","callback_query"],drop_pending_updates:false});
      return resultPage("اتصال وب‌هوک تلگرام با موفقیت ثبت شد. نتیجه تلگرام: " + result, true);
    } catch (e) { return resultPage("تلگرام اتصال را نپذیرفت: " + e.message); }
  }
  if (path === "/dashboard/github") {
    const expected = env.DASHBOARD_ACCESS_KEY || "";
    if (!expected || key.length !== expected.length || [...key].reduce((n,c,i)=>n|(c.charCodeAt(0)^expected.charCodeAt(i)),0)!==0) return resultPage("کلید دسترسی پنل معتبر نیست.");
    try {
      const repos = await githubRequest(env, "/user/repos?visibility=all&affiliation=owner&sort=updated&per_page=100");
      const items = repos.filter(x=>x.owner && x.owner.login.toLowerCase()==="onlysingbox").slice(0,50);
      const list = items.map(x=>"<li><a style='color:#8bd7b1' href='"+escapeHtml(x.html_url)+"'>"+escapeHtml(x.name)+"</a> | "+(x.private?"خصوصی":"عمومی")+" | "+escapeHtml(x.default_branch)+"</li>").join("");
      return resultPage("اتصال GitHub موفق بود. تعداد مخازن پیدا شده: "+items.length, true, "<ul>"+list+"</ul>");
    } catch(e) { return resultPage("خطا در دریافت مخازن GitHub: "+e.message); }
  }
  return resultPage("مسیر درخواست ناشناخته است.");
}

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
  const [rawCommand, ...rest] = text.split(/\s+/);
  const command = rawCommand.split("@")[0].toLowerCase();
  const arg = rest.join(" ");
  if (command === "/start") {
    await reply(env, chatId, "🐺 به WOLF AI خوش آمدی.\n\nدستیار شخصی تو آماده دریافت وظیفه است. عملیات حساس فقط پس از تأیید تو انجام می‌شوند.\n\nبرای دیدن فرمان‌ها /help را بزن.");
  } else if (command === "/help") {
    await reply(env, chatId, "📖 راهنمای WOLF AI\n\n/status - بررسی واقعی وضعیت سیستم، تلگرام، GitHub و پایگاه داده\n/newtask عنوان کار - ثبت وظیفه جدید\n/tasks - نمایش ۱۰ وظیفه اخیر\n/approve شناسه - تأیید طرح وظیفه\n/reject شناسه - رد وظیفه\n\n⚠️ تأیید فعلاً فقط طرح را تأیید می‌کند و هنوز هیچ ابزار خارجی اجرا نمی‌شود.");
  } else if (command === "/status") {
    const checks = await Promise.all([
      (async () => { try { const r = await env.DB.prepare("SELECT COUNT(*) AS total FROM tasks").first(); const t = await env.DB.prepare("SELECT COUNT(*) AS n FROM sqlite_master WHERE type='table' AND name IN ('users','projects','tasks','approvals','activity_logs','settings')").first(); return {ok:t.n===6, text:t.n===6 ? "متصل؛ ۶ جدول اصلی موجود" : "ساختار ناقص", tasks:r.total}; } catch(e) { return {ok:false,text:"خطا: "+e.message,tasks:null}; } })(),
      (async () => { try { const me = await telegram(env,"getMe",{}); const wh = await telegram(env,"getWebhookInfo",{}); const expected = new URL("/telegram/webhook", new URL(request.url).origin).toString(); return {ok:!!me.is_bot && wh.url===expected, text:(me.username ? "@"+me.username : "ربات شناسایی شد")+"؛ وب‌هوک "+(wh.url===expected ? "فعال و صحیح" : "نیازمند بررسی")+(wh.pending_update_count ? "؛ پیام‌های در انتظار: "+wh.pending_update_count : "")+(wh.last_error_message ? "؛ خطای اخیر: "+wh.last_error_message : "")}; } catch(e) { return {ok:false,text:"خطا: "+e.message}; } })(),
      (async () => { try { const u = await githubRequest(env,"/user"); return {ok:!!u.login,text:"متصل؛ حساب "+u.login}; } catch(e) { return {ok:false,text:"خطا: "+e.message}; } })()
    ]);
    const [db, tg, gh] = checks;
    const mark = x => x.ok ? "✅" : "⚠️";
    await reply(env, chatId, "🐺 گزارش وضعیت WOLF AI\n\nWorker: ✅ فعال و در حال پاسخ‌گویی\nپایگاه داده D1: "+mark(db)+" "+db.text+"\nتلگرام: "+mark(tg)+" "+tg.text+"\nGitHub: "+mark(gh)+" "+gh.text+"\nتعداد وظایف ثبت‌شده: "+(db.tasks ?? "نامشخص")+"\nحالت امنیتی: تأیید مالک پیش از عملیات حساس\n\nتوجه: اجرای خودکار ابزارهای خارجی هنوز فعال نشده است.");
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
    if (request.method === "POST" && (url.pathname === "/dashboard/connect" || url.pathname === "/dashboard/github")) {
      return await handleDashboardPost(request, env, url.pathname);
    }
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