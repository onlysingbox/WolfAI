package com.onlysingbox.wolfai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

data class CoinQuote(
    val id: String,
    val symbol: String,
    val name: String,
    val priceUsd: Double,
    val change24h: Double?,
    val marketCapUsd: Double?,
    val volume24hUsd: Double?,
    val source: String = "CoinGecko",
    val updatedAt: String? = null
)

object MarketData {
    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WOLF-AI-Android/0.7")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("منبع بازار پاسخ HTTP $code داد")
            return body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun search(query: String): List<CoinQuote> = withContext(Dispatchers.IO) {
        val q = query.trim()
        require(q.isNotBlank()) { "نام یا نماد رمزارز را وارد کن." }
        val geckoAttempt = runCatching { searchCoinGecko(q) }
        geckoAttempt.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val paprikaAttempt = runCatching { searchCoinPaprika(q) }
        paprikaAttempt.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        throw IllegalStateException(
            "CoinGecko و CoinPaprika هر دو در دسترس نبودند یا نتیجه‌ای پیدا نکردند. " +
                (geckoAttempt.exceptionOrNull()?.message
                    ?: paprikaAttempt.exceptionOrNull()?.message
                    ?: "برای این عبارت رمزارزی پیدا نشد.")
        )
    }

    private fun searchCoinGecko(q: String): List<CoinQuote> {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val search = JSONObject(get("https://api.coingecko.com/api/v3/search?query=$encoded"))
        val coins = search.optJSONArray("coins") ?: JSONArray()
        val ids = (0 until minOf(coins.length(), 8)).mapNotNull {
            coins.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank)
        }
        if (ids.isEmpty()) return emptyList()
        val markets = JSONArray(
            get("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=${ids.joinToString(",")}&price_change_percentage=24h")
        )
        return (0 until markets.length()).mapNotNull { i ->
            markets.optJSONObject(i)?.let { obj ->
                val price = obj.optDouble("current_price", Double.NaN)
                if (!price.isFinite()) return@let null
                CoinQuote(
                    id = obj.optString("id"),
                    symbol = obj.optString("symbol").uppercase(),
                    name = obj.optString("name"),
                    priceUsd = price,
                    change24h = if (obj.isNull("price_change_percentage_24h")) null else obj.optDouble("price_change_percentage_24h"),
                    marketCapUsd = if (obj.isNull("market_cap")) null else obj.optDouble("market_cap"),
                    volume24hUsd = if (obj.isNull("total_volume")) null else obj.optDouble("total_volume"),
                    source = "CoinGecko",
                    updatedAt = obj.optString("last_updated").takeIf(String::isNotBlank)
                )
            }
        }
    }

    private suspend fun searchCoinPaprika(q: String): List<CoinQuote> {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val search = JSONObject(get("https://api.coinpaprika.com/v1/search/?q=$encoded&c=currencies&limit=5"))
        val currencies = search.optJSONArray("currencies") ?: JSONArray()
        val candidates = (0 until minOf(currencies.length(), 5)).mapNotNull { i ->
            currencies.optJSONObject(i)?.takeIf { it.optBoolean("is_active", true) }
        }
        if (candidates.isEmpty()) return emptyList()
        return coroutineScope {
            candidates.map { coin ->
                async(Dispatchers.IO) {
                val id = coin.optString("id")
                require(id.matches(Regex("[a-z0-9-]{2,100}"))) { "شناسه رمزارز نامعتبر است." }
                val ticker = JSONObject(get("https://api.coinpaprika.com/v1/tickers/$id"))
                val usd = ticker.optJSONObject("quotes")?.optJSONObject("USD")
                    ?: error("قیمت دلاری برای ${coin.optString("name")} موجود نیست.")
                val price = usd.optDouble("price", Double.NaN)
                if (!price.isFinite()) error("قیمت معتبر از CoinPaprika دریافت نشد.")
                CoinQuote(
                    id = id,
                    symbol = coin.optString("symbol").uppercase(),
                    name = coin.optString("name"),
                    priceUsd = price,
                    change24h = if (usd.isNull("percent_change_24h")) null else usd.optDouble("percent_change_24h"),
                    marketCapUsd = if (usd.isNull("market_cap")) null else usd.optDouble("market_cap"),
                    volume24hUsd = if (usd.isNull("volume_24h")) null else usd.optDouble("volume_24h"),
                    source = "CoinPaprika",
                    updatedAt = ticker.optString("last_updated").takeIf(String::isNotBlank)
                )
                }
            }.awaitAll()
        }
    }

    fun format(quote: CoinQuote): String = buildString {
        append("${quote.name} (${quote.symbol})\n")
        append("قیمت: $${quote.priceUsd}\n")
        append("تغییر ۲۴ ساعت: ${quote.change24h?.let { "%.2f%%".format(java.util.Locale.US, it) } ?: "ناموجود"}\n")
        append("ارزش بازار: ${quote.marketCapUsd ?: "ناموجود"} USD\n")
        append("حجم ۲۴ ساعت: ${quote.volume24hUsd ?: "ناموجود"} USD\n")
        append("منبع: ${quote.source}")
        quote.updatedAt?.let { append("\nآخرین به‌روزرسانی منبع: $it") }
        append("\nداده‌ها ممکن است با تأخیر باشند. این خروجی توصیه خرید یا فروش نیست.")
    }
}
