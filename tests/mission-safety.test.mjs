import test from "node:test";
import assert from "node:assert/strict";
import { validateCompanionReadOnlyAction, executeCompanionReadOnlyAction } from "../src/worker.js";

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
