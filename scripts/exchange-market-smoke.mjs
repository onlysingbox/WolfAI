const checks = [
  {
    name: "Nobitex BTC/IRT public stats",
    url: "https://apiv2.nobitex.ir/market/stats?srcCurrency=btc&dstCurrency=rls",
    validate(data) {
      if (data?.status !== "ok") throw new Error("Nobitex status is not ok");
      const quote = data?.stats?.["btc-rls"];
      const price = Number(quote?.latest);
      if (!Number.isFinite(price) || price <= 0) throw new Error("Nobitex BTC/IRT latest price is missing");
      if (!Number.isFinite(Number(quote?.dayChange))) throw new Error("Nobitex 24h change is missing");
      return { priceRial: price, change24hPercent: Number(quote.dayChange) };
    }
  },
  {
    name: "Bitpin BTC/IRT public ticker",
    url: "https://api.bitpin.org/api/v1/mkt/tickers/",
    validate(data) {
      const rows = data?.body ?? data?.results;
      if (!Array.isArray(rows)) throw new Error("Bitpin ticker response has no body/results array; response=" + JSON.stringify(data).slice(0, 1000));
      const quote = rows.find(row => String(row?.symbol).toUpperCase() === "BTC_IRT");
      const price = Number(quote?.price);
      if (!Number.isFinite(price) || price <= 0) throw new Error("Bitpin BTC/IRT price is missing");
      const ratio = Number(quote?.daily_change_price);
      if (!Number.isFinite(ratio)) throw new Error("Bitpin daily_change_price is missing");
      return { priceToman: price, change24hPercent: ratio * 100 };
    }
  }
];

let failed = 0;
for (const check of checks) {
  try {
    const response = await fetch(check.url, {
      headers: { accept: "application/json", "user-agent": "WOLF-AI-CI-Market-Smoke/1.0" },
      signal: AbortSignal.timeout(20000)
    });
    if (!response.ok) throw new Error("HTTP " + response.status);
    const data = await response.json();
    const result = check.validate(data);
    process.stdout.write("PASS " + check.name + " " + JSON.stringify(result) + "\n");
  } catch (error) {
    failed++;
    process.stderr.write("FAIL " + check.name + ": " + String(error?.message || error) + "\n");
  }
}
if (failed) process.exitCode = 1;
