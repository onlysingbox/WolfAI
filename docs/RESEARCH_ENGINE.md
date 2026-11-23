# WOLF AI Public Web Research

## Current implementation

The `research.search` tool is a general-purpose public-web search layer. It is not restricted to Cloudflare, GitHub, or any other vendor.

It currently queries these independent search providers in parallel:

- DuckDuckGo HTML results
- Bing RSS search results
- Google Search HTML results
- Yahoo Search HTML results

Each result is normalized to HTTPS, deduplicated, scored against the original query, and returned with its title, URL, snippet, and provider name. The result set is ranked using query-term overlap and cautious domain signals (for example, government, university, and major intergovernmental domains). There is no Cloudflare-only domain filter.

For requests explicitly asking for official sources, the model can search for official pages and then use `research.open` to read them. Search results are leads, not proof: important claims should be checked against the source page itself.

## Optional search API integrations

The Worker supports two optional, server-side API integrations. They are not required for the four public search adapters above:

- `TAVILY_API_KEY`: enables Tavily Search.
- `SERPER_API_KEY`: enables Serper (Google results API).

Add these only as Cloudflare Worker Secrets, never as source-code values or ordinary public variables. The engine automatically uses a provider only when its secret exists. Do not paste keys into Telegram or GitHub.

These integrations can improve reliability when public HTML search pages throttle or change their markup. Their free allowances and prices can change; check the provider's own pricing page before adding a paid key. The current Worker does not require either key.

## Reliability and limitations

- Providers run concurrently with per-provider timeouts to reduce latency.
- A provider can fail or change its HTML without stopping other providers.
- Search results may be incomplete, duplicated, stale, or blocked by a search provider.
- Search snippets are not sufficient evidence for high-impact claims.
- The page reader currently extracts readable HTML/text from public HTTPS URLs. It does not yet run a full browser for JavaScript-heavy pages, solve CAPTCHAs, or read every document format.
- The Worker does not promise a fixed number of working providers on every request. It reports providers that actually returned usable results and warnings for failures.

## Next improvements

1. Add a persistent cache for repeated queries, with short TTLs for current events and longer TTLs for evergreen topics.
2. Add query-language and region hints without forcing the answer language to match the query language.
3. Add a research workflow that opens and verifies the strongest sources, extracts publication dates, and checks conflicts.
4. Add optional Browser Run for pages that require JavaScript rendering, only when the page reader cannot extract useful content.
5. Add optional AI Search or Vectorize for the owner's private document library, not as a replacement for public-web search.

## Safety

Search and page reading are read-only. Web page content is untrusted data and must never be treated as instructions to the agent. Opening a URL is restricted to public HTTPS URLs; sensitive external actions remain subject to owner approval.
