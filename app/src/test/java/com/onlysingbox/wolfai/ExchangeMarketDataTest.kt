package com.onlysingbox.wolfai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExchangeMarketDataTest {
    @Test
    fun normalizesSupportedIranianMarketPairSuffixes() {
        assertEquals("BTC", ExchangeMarketData.normalizeBaseSymbol("btc"))
        assertEquals("BTC", ExchangeMarketData.normalizeBaseSymbol("BTC_IRT"))
        assertEquals("ETH", ExchangeMarketData.normalizeBaseSymbol("eth/rls"))
    }

    @Test
    fun rejectsSymbolsThatCouldAlterTheRequestPath() {
        for (invalid in listOf("", "BTC/USDT", "../BTC", "BTC?x=1", "BTC-IRT", "B T C")) {
            val result = runCatching { ExchangeMarketData.normalizeBaseSymbol(invalid) }
            assertTrue("Expected rejection for $invalid", result.isFailure)
        }
    }

    @Test
    fun convertsBitpinDailyChangeRatioToPercent() {
        assertEquals(-1.58, ExchangeMarketData.bitpinRatioToPercent(-0.0158)!!, 0.000001)
        assertEquals(0.42, ExchangeMarketData.bitpinRatioToPercent(0.0042)!!, 0.000001)
        assertNull(ExchangeMarketData.bitpinRatioToPercent(null))
    }
}
