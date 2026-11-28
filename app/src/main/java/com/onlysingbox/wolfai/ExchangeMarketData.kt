package com.onlysingbox.wolfai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

data class ExchangeQuote(
    val exchange: String,
    val symbol: String,
    val lastPriceRial: Double,
    val change24hPercent: Double?,
    val highRial: Double?,
    val lowRial: Double?,
    val quoteVolumeRial: Double?,
    val updatedAt: String?,
    val marketClosed: Boolean? = null
) {
    val lastPriceToman: Double get() = lastPriceRial / 10.0
}

object ExchangeMarketData {
    fun normalizeBaseSymbol(raw: String): String {
        val symbol = raw.trim().uppercase(Locale.ROOT).removeSuffix("/IRT").removeSuffix("_IRT")
            .removeSuffix("/RLS").removeSuffix("_RLS")
        require(symbol.matches(Regex("[A-Z0-9]{2,12}"))) {
            "نماد را فقط با حروف و اعداد وارد کن؛ نمونه: BTC یا ETH."
        }
        return symbol
    }

    fun bitpinRatioToPercent(value: Double?): Double? =
        value?.takeIf { it.isFinite() }?.times(100.0)

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
            if (code !in 200..299) error("صرافی پاسخ HTTP $code داد.")
            return body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun nobitex(rawSymbol: String): ExchangeQuote = withContext(Dispatchers.IO) {
        val base = normalizeBaseSymbol(rawSymbol).lowercase(Locale.ROOT)
        val url = "https://apiv2.nobitex.ir/market/stats?srcCurrency=" +
            URLEncoder.encode(base, "UTF-8") + "&dstCurrency=rls"
        val root = JSONObject(get(url))
        if (root.optString("status") != "ok") error("نوبیتکس اطلاعات بازار این نماد را برنگرداند.")
        val stats = root.optJSONObject("stats") ?: error("پاسخ آمار نوبیتکس نامعتبر است.")
        val item = stats.optJSONObject("$base-rls") ?: error("بازار ${base.uppercase()} به ریال در نوبیتکس پیدا نشد.")
        val last = item.optDouble("latest", Double.NaN)
        if (!last.isFinite() || last <= 0.0) error("قیمت معتبر از نوبیتکس دریافت نشد.")
        ExchangeQuote(
            exchange = "Nobitex",
            symbol = base.uppercase(Locale.ROOT) + "/IRT",
            lastPriceRial = last,
            change24hPercent = item.optDouble("dayChange", Double.NaN).takeIf { it.isFinite() },
            highRial = item.optDouble("dayHigh", Double.NaN).takeIf { it.isFinite() },
            lowRial = item.optDouble("dayLow", Double.NaN).takeIf { it.isFinite() },
            quoteVolumeRial = item.optDouble("volumeDst", Double.NaN).takeIf { it.isFinite() },
            updatedAt = "زمان دریافت: ${Instant.now()}",
            marketClosed = item.optBoolean("isClosed")
        )
    }

    suspend fun bitpin(rawSymbol: String): ExchangeQuote = withContext(Dispatchers.IO) {
        val base = normalizeBaseSymbol(rawSymbol)
        val root = JSONObject(get("https://api.bitpin.org/api/v1/mkt/tickers/"))
        val status = root.optInt("status", 200)
        if (status !in 200..299) error("بیت‌پین پاسخ معتبر نداد (HTTP $status).")
        val rows = root.optJSONArray("body") ?: root.optJSONArray("results")
            ?: error("ساختار پاسخ تیکر بیت‌پین تغییر کرده یا معتبر نیست.")
        val market = findBitpinTicker(rows, base + "_IRT")
            ?: error("بازار ${base}/IRT در بیت‌پین پیدا نشد.")
        val price = market.optDouble("price", Double.NaN)
        if (!price.isFinite() || price <= 0.0) error("قیمت معتبر از بیت‌پین دریافت نشد.")
        val timestamp = market.optDouble("timestamp", Double.NaN)
        ExchangeQuote(
            exchange = "Bitpin",
            symbol = base + "/IRT",
            // Bitpin's IRT quote asset is Toman; normalize internal storage to Rial.
            lastPriceRial = price * 10.0,
            change24hPercent = bitpinRatioToPercent(market.optDouble("daily_change_price", Double.NaN).takeIf { it.isFinite() }),
            highRial = market.optDouble("high", Double.NaN).takeIf { it.isFinite() }?.times(10.0),
            lowRial = market.optDouble("low", Double.NaN).takeIf { it.isFinite() }?.times(10.0),
            quoteVolumeRial = null,
            updatedAt = timestamp.takeIf { it.isFinite() && it > 0.0 }?.let {
                Instant.ofEpochMilli((it * 1000.0).toLong()).toString()
            }
        )
    }

    internal fun findBitpinTicker(rows: JSONArray, symbol: String): JSONObject? {
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            if (item.optString("symbol").equals(symbol, ignoreCase = true)) return item
        }
        return null
    }
}
