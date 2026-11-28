package com.onlysingbox.wolfai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataTest {
    @Test
    fun blankSearchIsRejectedBeforeNetworkAccess() {
        val error = runCatching { runBlocking { MarketData.search("   ") } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("نام یا نماد رمزارز را وارد کن.", error?.message)
    }

    @Test
    fun formattedQuoteIdentifiesItsProviderAndTimestamp() {
        val quote = CoinQuote(
            id = "btc-bitcoin",
            symbol = "BTC",
            name = "Bitcoin",
            priceUsd = 65000.0,
            change24h = 1.25,
            marketCapUsd = 1_000_000.0,
            volume24hUsd = 20_000.0,
            source = "CoinPaprika",
            updatedAt = "2026-11-28T12:00:00Z"
        )

        val formatted = MarketData.format(quote)

        assertTrue(formatted.contains("Bitcoin (BTC)"))
        assertTrue(formatted.contains("CoinPaprika"))
        assertTrue(formatted.contains("2026-11-28T12:00:00Z"))
        assertTrue(formatted.contains("توصیه خرید یا فروش نیست"))
    }

    @Test
    fun quoteWithoutOptionalMarketFieldsFormatsSafely() {
        val quote = CoinQuote(
            id = "unknown-coin",
            symbol = "UNK",
            name = "Unknown",
            priceUsd = 0.0,
            change24h = null,
            marketCapUsd = null,
            volume24hUsd = null,
            source = "CoinGecko"
        )

        val formatted = MarketData.format(quote)

        assertTrue(formatted.contains("ناموجود"))
        assertEquals("CoinGecko", quote.source)
    }
}
