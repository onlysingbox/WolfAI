import test from "node:test";
import assert from "node:assert/strict";
import { validateCompanionReadOnlyAction, executeCompanionReadOnlyAction, claimCompanionMissionStep, getOwnedCompanionMission } from "../src/worker.js";

test("accepts a bounded web search", () => {
  assert.deepEqual(
    validateCompanionReadOnlyAction("web_research", { type: "research.search", args: { query: "Cloudflare Workers" } }),
    { type: "research.search", args: { query: "Cloudflare Workers" } }
  );
});

test("rejects write actions even when the model proposes them", () => {
  assert.throws(() => validateCompanionReadOnlyAction("personal_memory", {
    type: "memory.save", args: { key: "x", value: "y" }
  }));
});

test("rejects an action that does not match the approved capability", () => {
  assert.throws(() => validateCompanionReadOnlyAction("web_research", {
    type: "finance.summary", args: { start_date: "2026-01-01", end_date: "2026-01-31" }
  }));
});

test("rejects non-HTTPS and local/private web targets", () => {
  for (const url of [
    "http://example.com",
    "https://localhost/",
    "https://127.0.0.1/",
    "https://192.168.1.10/",
    "https://service.internal/",
    "https://example.com:8443/"
  ]) {
    assert.throws(() => validateCompanionReadOnlyAction("web_research", {
      type: "research.open", args: { url }
    }), url);
  }
});

test("accepts a public HTTPS page", () => {
  const result = validateCompanionReadOnlyAction("web_research", {
    type: "research.open", args: { url: "https://example.com/article" }
  });
  assert.equal(result.type, "research.open");
  assert.equal(new URL(result.args.url).hostname, "example.com");
});

test("rejects impossible finance dates and malformed currency", () => {
  assert.throws(() => validateCompanionReadOnlyAction("finance", {
    type: "finance.summary", args: { start_date: "2026-02-30", end_date: "2026-03-01" }
  }));
  assert.throws(() => validateCompanionReadOnlyAction("finance", {
    type: "finance.summary", args: { start_date: "2026-02-01", end_date: "2026-02-28", currency: "US$" }
  }));
});

test("rejects GitHub path traversal and extra parameters", () => {
  assert.throws(() => validateCompanionReadOnlyAction("files_documents", {
    type: "github.get_file", args: { repo: "WolfAI", path: "../secrets" }
  }));
  assert.throws(() => validateCompanionReadOnlyAction("files_documents", {
    type: "github.get_file", args: { repo: "WolfAI", path: "README.md", ref: "main" }
  }));
});

test("rejects unknown action parameters", () => {
  assert.throws(() => validateCompanionReadOnlyAction("web_research", {
    type: "research.search", args: { query: "test", execute: true }
  }));
});

test("the execution wrapper rejects writes before touching storage", async () => {
  let dbTouched = false;
  const env = { DB: { prepare() { dbTouched = true; throw new Error("DB must not be reached"); } } };
  await assert.rejects(
    executeCompanionReadOnlyAction(env, "personal_memory", {
      type: "memory.save", args: { key: "private", value: "must not write" }
    })
  );
  assert.equal(dbTouched, false);
});

test("the execution wrapper rejects sequence envelopes", async () => {
  const env = { DB: { prepare() { throw new Error("DB must not be reached"); } } };
  await assert.rejects(
    executeCompanionReadOnlyAction(env, "web_research", {
      type: "agent.sequence", args: { steps: [{ type: "research.search", args: { query: "x" } }] }
    })
  );
});

test("MASTER OFF blocks the read-only executor before external work", async () => {
  const env = { DB: { prepare(sql) { return { bind(key) { return { async first() { return { value: "false" }; } }; } }; } } };
  await assert.rejects(
    executeCompanionReadOnlyAction(env, "web_research", {
      type: "research.search", args: { query: "must not run" }
    }),
    /MASTER OFF/
  );
});

test("owned mission lookup is scoped to the authenticated owner", async () => {
  let sqlSeen = "";
  let bindings = [];
  const env = { DB: { prepare(sql) {
    sqlSeen = sql;
    return { bind(...args) {
      bindings = args;
      return { async first() {
        return args[1] === "owner-A"
          ? { id: args[0], status: "approved_pending_execution", owner_id: args[1] }
          : null;
      } };
    } };
  } } };
  assert.equal((await getOwnedCompanionMission(env, "mission-1", "owner-A")).owner_id, "owner-A");
  assert.equal(await getOwnedCompanionMission(env, "mission-1", "owner-B"), null);
  assert.match(sqlSeen, /owner_id=\?/);
  assert.deepEqual(bindings, ["mission-1", "owner-B"]);
});

function makeClaimDb({ runOwner = "owner-A", runStatus = "approved_pending_execution", steps = [] } = {}) {
  const state = { steps: structuredClone(steps), runOwner, runStatus, sql: "" };
  return {
    state,
    prepare(sql) {
      state.sql = sql;
      return {
        bind(...args) {
          return {
            async run() {
              const [stepId, missionId, stepNo, ownerId, runningRunId, priorRunId, priorStepNo, existsRunId, existsOwner] = args;
              assert.equal(missionId, runningRunId);
              assert.equal(missionId, priorRunId);
              assert.equal(missionId, existsRunId);
              const step = state.steps.find(x => x.id === stepId && x.run_id === missionId && x.step_no === stepNo);
              const alreadyRunning = state.steps.some(x => x.run_id === missionId && x.status === "running");
              const priorIncomplete = state.steps.some(x => x.run_id === missionId && x.step_no < priorStepNo && x.status !== "completed");
              const eligibleRun = state.runOwner === existsOwner && state.runStatus === "approved_pending_execution" || state.runOwner === existsOwner && state.runStatus === "running";
              const eligibleStep = step && step.status === "awaiting_execution_approval" && step.approval_required === 1 && step.approved_by === ownerId;
              if (!eligibleStep || alreadyRunning || priorIncomplete || !eligibleRun) return { meta: { changes: 0 } };
              step.status = "running";
              return { meta: { changes: 1 } };
            }
          };
        }
      };
    }
  };
}

test("D1 claim gate allows exactly one concurrent execution claim", async () => {
  const db = makeClaimDb({ steps: [{ id: "s1", run_id: "m1", step_no: 1, status: "awaiting_execution_approval", approval_required: 1, approved_by: "owner-A" }] });
  const args = { stepId: "s1", missionId: "m1", stepNo: 1, ownerId: "owner-A" };
  const results = await Promise.all([
    claimCompanionMissionStep({ DB: db }, args),
    claimCompanionMissionStep({ DB: db }, args)
  ]);
  assert.equal(results.filter(Boolean).length, 1);
  assert.equal(db.state.steps[0].status, "running");
  assert.match(db.state.sql, /NOT EXISTS/);
});

test("D1 claim gate blocks a later step until every prior step is completed", async () => {
  const db = makeClaimDb({ steps: [
    { id: "s1", run_id: "m1", step_no: 1, status: "approved_pending_execution", approval_required: 1, approved_by: "owner-A" },
    { id: "s2", run_id: "m1", step_no: 2, status: "awaiting_execution_approval", approval_required: 1, approved_by: "owner-A" }
  ] });
  assert.equal(await claimCompanionMissionStep({ DB: db }, { stepId: "s2", missionId: "m1", stepNo: 2, ownerId: "owner-A" }), false);
  assert.equal(db.state.steps[1].status, "awaiting_execution_approval");
});

test("D1 claim gate rejects another owner's approval and a running mission step", async () => {
  const wrongOwner = makeClaimDb({ steps: [{ id: "s1", run_id: "m1", step_no: 1, status: "awaiting_execution_approval", approval_required: 1, approved_by: "owner-B" }] });
  assert.equal(await claimCompanionMissionStep({ DB: wrongOwner }, { stepId: "s1", missionId: "m1", stepNo: 1, ownerId: "owner-A" }), false);
  const active = makeClaimDb({ steps: [
    { id: "s0", run_id: "m1", step_no: 0, status: "running", approval_required: 1, approved_by: "owner-A" },
    { id: "s1", run_id: "m1", step_no: 1, status: "awaiting_execution_approval", approval_required: 1, approved_by: "owner-A" }
  ] });
  assert.equal(await claimCompanionMissionStep({ DB: active }, { stepId: "s1", missionId: "m1", stepNo: 1, ownerId: "owner-A" }), false);
});


import { wolfProviderCatalog, wolfProviderOrder, wolfHasAnyProvider, WOLF_EXTERNAL_PROVIDERS, WOLF_AI_MODELS } from "../src/worker.js";

test("provider catalog reports only actually configured engines", () => {
  const env = { AI: { run() {} }, GEMINI_API_KEY: "configured", GROQ_API_KEY: "", ZAI_API_KEY: "configured", OPENROUTER_API_KEY: "configured" };
  const catalog = wolfProviderCatalog(env);
  assert.equal(catalog.find(x => x.id === "glm").configured, true);
  assert.equal(catalog.find(x => x.id === "gemma").configured, true);
  assert.equal(catalog.find(x => x.id === "gemini").configured, true);
  assert.equal(catalog.find(x => x.id === "groq").configured, false);
  assert.equal(catalog.find(x => x.id === "zai").configured, true);
  assert.equal(catalog.find(x => x.id === "openrouter").configured, true);
  assert.ok(catalog.filter(x => ["glm","gemma","gemini","groq","zai","openrouter"].includes(x.id)).every(x => x.freeOnly));
});

test("automatic routing falls back only across configured free engines", () => {
  const env = { AI: { run() {} }, GEMINI_API_KEY: "g", GROQ_API_KEY: "", ZAI_API_KEY: "z", OPENROUTER_API_KEY: "o" };
  assert.deepEqual(wolfProviderOrder(env, "auto", false), ["glm","zai","gemma","gemini","openrouter"]);
  assert.deepEqual(wolfProviderOrder(env, "auto", true), ["zai","gemma","glm","gemini","openrouter"]);
  assert.throws(() => wolfProviderOrder(env, "groq", false), /هنوز کلید API/);
});

test("provider model identifiers are current and never select the retired Groq Llama model", () => {
  assert.equal(WOLF_AI_MODELS.glm, "@cf/zai-org/glm-4.7-flash");
  assert.equal(WOLF_AI_MODELS.gemma, "@cf/google/gemma-4-26b-a4b-it");
  assert.equal(WOLF_EXTERNAL_PROVIDERS.gemini.model, "gemini-2.5-flash");
  assert.equal(WOLF_EXTERNAL_PROVIDERS.groq.model, "openai/gpt-oss-120b");
  assert.equal(WOLF_EXTERNAL_PROVIDERS.zai.model, "glm-4.7-flash");
  assert.equal(WOLF_EXTERNAL_PROVIDERS.openrouter.model, "meta-llama/llama-3.3-70b-instruct:free");
});


import { callWolfProvider } from "../src/worker.js";

test("all six provider adapters parse successful mocked responses without exposing keys", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), init });
    if (String(url).includes("generativelanguage.googleapis.com")) {
      return new Response(JSON.stringify({
        candidates: [{ content: { parts: [{ text: "Gemini test reply" }] } }],
        usageMetadata: { promptTokenCount: 4, candidatesTokenCount: 3 }
      }), { status: 200, headers: { "content-type": "application/json" } });
    }
    return new Response(JSON.stringify({
      choices: [{ message: { content: "OpenAI compatible test reply" } }],
      usage: { prompt_tokens: 5, completion_tokens: 4 }
    }), { status: 200, headers: { "content-type": "application/json" } });
  };
  try {
    const messages = [{ role: "user", content: "test" }];
    const cloudflareEnv = { AI: { async run(model) { return { choices: [{ message: { content: model + " reply" } }], usage: { total_tokens: 2 } }; } } };
    assert.match((await callWolfProvider(cloudflareEnv, "glm", messages)).answer, /glm-4.7-flash/);
    assert.match((await callWolfProvider(cloudflareEnv, "gemma", messages)).answer, /gemma-4-26b-a4b-it/);
    const gemini = await callWolfProvider({ GEMINI_API_KEY: "never-log-this" }, "gemini", messages);
    assert.equal(gemini.answer, "Gemini test reply");
    assert.equal(gemini.usage.promptTokenCount, 4);
    const groq = await callWolfProvider({ GROQ_API_KEY: "never-log-this" }, "groq", messages);
    assert.equal(groq.answer, "OpenAI compatible test reply");
    assert.match(calls.at(-1).init.body, /openai\/gpt-oss-120b/);
    const zai = await callWolfProvider({ ZAI_API_KEY: "never-log-this" }, "zai", messages);
    assert.equal(zai.answer, "OpenAI compatible test reply");
    assert.equal(calls.at(-1).url, "https://api.z.ai/api/paas/v4/chat/completions");
    assert.equal(calls.at(-1).init.headers.authorization, "Bearer never-log-this");
    assert.match(calls.at(-1).init.body, /glm-4\.7-flash/);
    const openrouter = await callWolfProvider({ OPENROUTER_API_KEY: "never-log-this" }, "openrouter", messages);
    assert.equal(openrouter.answer, "OpenAI compatible test reply");
    assert.equal(calls.at(-1).init.headers["HTTP-Referer"], "https://wolfai.onlysingbox.workers.dev");
    assert.match(calls.at(-1).init.body, /llama-3\.3-70b-instruct:free/);
    assert.ok(calls.every(x => !x.url.includes("never-log-this")));
  } finally {
    globalThis.fetch = originalFetch;
  }
});


test("chat availability accepts Z.ai as the only configured provider", () => {
  assert.equal(wolfHasAnyProvider({ ZAI_API_KEY: "configured" }), true);
  assert.equal(wolfHasAnyProvider({ GROQ_API_KEY: "configured" }), true);
  assert.equal(wolfHasAnyProvider({ AI: { run() {} } }), true);
  assert.equal(wolfHasAnyProvider({}), false);
});
