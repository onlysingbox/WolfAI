package com.onlysingbox.wolfai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CoinQuote(
    val id: String,
    val symbol: String,
    val name: String,
    val priceUsd: Double,
    val change24h: Double?,
    val marketCapUsd: Double?,
    val volume24hUsd: Double?
)

object MarketData {
    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 18000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WOLF-AI-Android/0.7")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("منبع بازار پاسخ HTTP $code داد")
            return body
        } finally { connection.disconnect() }
    }

    suspend fun search(query: String): List<CoinQuote> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val q = query.trim()
        require(q.isNotBlank()) { "نام یا نماد رمزارز را وارد کن." }
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        val search = JSONObject(get("https://api.coingecko.com/api/v3/search?query=$encoded"))
        val coins = search.optJSONArray("coins") ?: JSONArray()
        val ids = (0 until minOf(coins.length(), 8)).mapNotNull { coins.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        if (ids.isEmpty()) error("رمزارزی با این نام پیدا نشد.")
        val markets = JSONArray(get("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=${ids.joinToString(",")}&price_change_percentage=24h"))
        (0 until markets.length()).mapNotNull { i ->
            markets.optJSONObject(i)?.let { obj ->
                CoinQuote(
                    id = obj.optString("id"),
                    symbol = obj.optString("symbol").uppercase(),
                    name = obj.optString("name"),
                    priceUsd = obj.optDouble("current_price", Double.NaN),
                    change24h = if (obj.isNull("price_change_percentage_24h")) null else obj.optDouble("price_change_percentage_24h"),
                    marketCapUsd = if (obj.isNull("market_cap")) null else obj.optDouble("market_cap"),
                    volume24hUsd = if (obj.isNull("total_volume")) null else obj.optDouble("total_volume")
                )
            }
        }
    }

    fun format(quote: CoinQuote): String = buildString {
        append("${quote.name} (${quote.symbol})\n")
        append("قیمت: $${quote.priceUsd}\n")
        append("تغییر ۲۴ ساعت: ${quote.change24h?.let { "%.2f%%".format(java.util.Locale.US, it) } ?: "ناموجود"}\n")
        append("ارزش بازار: ${quote.marketCapUsd ?: "ناموجود"} USD\n")
        append("حجم ۲۴ ساعت: ${quote.volume24hUsd ?: "ناموجود"} USD\n")
        append("منبع: CoinGecko؛ داده‌ها ممکن است با تأخیر باشند. این خروجی توصیه خرید یا فروش نیست.")
    }
}
