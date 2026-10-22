package com.example.stockalert

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Quotes {
    data class Quote(
        val symbol: String,
        val price: Double,
        val currency: String,
        val name: String
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // Yahoo Finance 비공식 차트 API (정규장 기준 현재가)
    fun fetch(symbol: String): Quote? {
        val hosts = listOf("query1", "query2")
        for (host in hosts) {
            try {
                val url = URL(
                    "https://" + host + ".finance.yahoo.com/v8/finance/chart/" +
                        URLEncoder.encode(symbol, "UTF-8") + "?interval=1m&range=1d"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val meta = JSONObject(body)
                    .getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0)
                    .getJSONObject("meta")
                if (!meta.has("regularMarketPrice")) continue
                val nm = meta.optString("shortName", meta.optString("longName", symbol))
                return Quote(
                    meta.optString("symbol", symbol),
                    meta.getDouble("regularMarketPrice"),
                    meta.optString("currency", ""),
                    nm
                )
            } catch (e: Exception) {
                // 다음 호스트 시도
            }
        }
        return null
    }

    // 국내 종목은 6자리 코드만 입력해도 코스피(.KS) -> 코스닥(.KQ) 순으로 찾음
    fun resolve(input: String): Quote? {
        val s = input.trim().uppercase()
        if (s.isEmpty()) return null
        if (Regex("^[0-9][0-9A-Z]{5}$").matches(s)) {
            return fetch(s + ".KS") ?: fetch(s + ".KQ")
        }
        return fetch(s)
    }

    // 여러 종목을 병렬로 조회 (브로드캐스트 처리 시간 제한 때문에 빠르게)
    fun fetchAll(symbols: List<String>): Map<String, Quote?> {
        val result = HashMap<String, Quote?>()
        if (symbols.isEmpty()) return result
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = symbols.map { sym -> Callable<Quote?> { fetch(sym) } }
            val futures = pool.invokeAll(tasks, 9, TimeUnit.SECONDS)
            for (i in symbols.indices) {
                val f = futures[i]
                val q: Quote? = try {
                    if (f.isCancelled) null else f.get()
                } catch (e: Exception) {
                    null
                }
                result[symbols[i]] = q
            }
        } finally {
            pool.shutdownNow()
        }
        return result
    }
}
