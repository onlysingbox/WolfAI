# Cloudflare capabilities assessment for WOLF AI (November 2026)

This is an implementation decision record, not a promise that every Cloudflare product is enabled in the account.

## Keep and use now

### Workers
The existing Worker remains the application runtime and API layer. It already hosts Telegram webhook handling, the agent orchestration code, and the public web research adapters.

### Workers AI
The current Worker uses the AI binding with GLM-4.7-Flash and Gemma 4 26B. Cloudflare's current Workers AI pricing documentation lists a Free allowance of 10,000 Neurons per day, then $0.011 per 1,000 Neurons above that allowance on Workers Paid. Some newer models have additional plan or prepaid-credit requirements. Keep model selection configurable and test a candidate model before switching production traffic.

### D1
D1 remains the best fit for WOLF AI's current structured state: owner memory, tasks, approvals, run history, documents, settings, and finance records. Current Free limits published by Cloudflare: 5 million rows read/day, 100,000 rows written/day, 5 GB total storage, and 10 databases. Query design and caching matter because a broad table scan can exhaust daily row reads.

## Useful, but only when the matching requirement appears

### AI Search
AI Search can index and retrieve the owner's private documents and website content for RAG. Current published Free allowances include 1,000 semantic queries/month, 1,000 full-text queries/month, and up to 500 crawled pages/day, with other ingestion and storage allowances. This is useful for a growing private knowledge base. It is not a general live-web search engine and should not replace the multi-engine research layer.

### Browser Run
Browser Run can render and interact with JavaScript-heavy pages, crawl pages, and support agent browser automation. It is a useful fallback when ordinary HTTP extraction fails. Kitesurf was announced as free during its beta in August 2026; beta terms and product limits can change. Do not make it a dependency for every search request.

### Agents SDK
The Agents SDK is a potential future refactor for durable, stateful, multi-step agent sessions, browser automation, and recovery after interruptions. It is not required to make the current Worker a general-purpose research assistant. Evaluate it when long-running jobs, resumability, or browser-driven workflows become a real bottleneck.

## Not needed for the current architecture

### Hyperdrive
Hyperdrive accelerates connections to external PostgreSQL/MySQL databases. The current project uses D1, so Hyperdrive adds no immediate value. Revisit only if WOLF AI moves substantial data to an external SQL database. Cloudflare's published Free allowance is 100,000 Hyperdrive queries/day.

### Vectorize
Vectorize is for vector similarity search over embeddings. It can help semantic retrieval across a large private document collection, but it is not a public-web search provider. Cloudflare's pricing documentation currently identifies Vectorize as Workers Paid; do not add it while the project is prioritizing zero-cost operation.

### Workerd
Workerd is the open-source runtime underlying Workers. It is useful for local development, testing, and runtime compatibility, not a separate managed feature that needs to be enabled to improve WOLF AI search.

## Chosen architecture

1. Public web: independent search providers in parallel, followed by source-page verification.
2. Private knowledge: D1 now; consider AI Search when the document collection grows.
3. Dynamic websites: ordinary HTTPS extraction first; Browser Run only as an on-demand fallback.
4. Agent orchestration: keep the current approval-gated Worker architecture; consider Agents SDK when durable long-running execution is needed.
5. Cost control: retain Workers Free-compatible defaults, use short timeouts and caching, and never assume a paid product is enabled.

## Official references

- Workers overview: https://developers.cloudflare.com/workers/
- Workers AI changelog: https://developers.cloudflare.com/changelog/product/workers-ai/
- Workers AI pricing: https://developers.cloudflare.com/workers-ai/platform/pricing/
- D1 pricing: https://developers.cloudflare.com/d1/platform/pricing/
- Hyperdrive pricing: https://developers.cloudflare.com/hyperdrive/platform/pricing/
- Workers pricing and Vectorize: https://developers.cloudflare.com/workers/platform/pricing/
- AI Search limits and pricing: https://developers.cloudflare.com/ai-search/platform/limits-pricing/
- Browser Run / developer platform changelog: https://developers.cloudflare.com/changelog/product-group/developer-platform/16/
- Workers changelog / Agents SDK: https://developers.cloudflare.com/changelog/product/workers/
