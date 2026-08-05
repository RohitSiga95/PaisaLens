package com.paisalens.app.data.network

import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.normalizedCurrency
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

class FrankfurterRateService {
    fun latestRate(fromCurrency: String, toCurrency: String): ExchangeRate {
        val from = fromCurrency.normalizedCurrency()
        val to = toCurrency.normalizedCurrency()
        require(from != to) { "Choose two different currencies" }
        val connection = URL("$API_ROOT/rate/$from/$to").openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PaisaLens-Android/1.4")
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            require(status == HttpsURLConnection.HTTP_OK) { "Rate service returned HTTP $status" }
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            parseRate(body, expectedBase = from, expectedQuote = to)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseRate(body: String, expectedBase: String, expectedQuote: String): ExchangeRate {
        val date = JSON_DATE.find(body)?.groupValues?.get(1) ?: error("Rate response has no date")
        val base = JSON_BASE.find(body)?.groupValues?.get(1) ?: error("Rate response has no base currency")
        val quote = JSON_QUOTE.find(body)?.groupValues?.get(1) ?: error("Rate response has no quote currency")
        val rate = JSON_RATE.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: error("Rate response has no valid rate")
        require(base == expectedBase && quote == expectedQuote && rate.isFinite() && rate > 0) {
            "Rate response did not match the requested currency pair"
        }
        return ExchangeRate(
            baseCurrency = quote,
            quoteCurrency = base,
            rate = rate,
            rateDate = date,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val API_ROOT = "https://api.frankfurter.dev/v2"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        val JSON_DATE = Regex("\\\"date\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val JSON_BASE = Regex("\\\"base\\\"\\s*:\\s*\\\"([A-Z]{3})\\\"")
        val JSON_QUOTE = Regex("\\\"quote\\\"\\s*:\\s*\\\"([A-Z]{3})\\\"")
        val JSON_RATE = Regex("\\\"rate\\\"\\s*:\\s*([-+0-9.eE]+)")
    }
}
