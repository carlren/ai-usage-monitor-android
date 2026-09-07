package com.carlren.aiusagemonitor

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class UsageWindow(
    val usedPercent: Int,
    val resetAtEpochSeconds: Long,
)

data class CodexUsage(
    val ok: Boolean,
    val planType: String,
    val creditBalance: Double?,
    val primaryWindow: UsageWindow?,
    val secondaryWindow: UsageWindow?,
    val cloudMessages: IntRange?,
    val localMessages: IntRange?,
)

data class OpenRouterUsage(
    val ok: Boolean,
    val remaining: Double,
    val totalCredits: Double,
    val totalUsage: Double,
)

data class MetaUsage(
    val balance: Double,
    val freeCredits: Double,
    val currency: String,
    val updatedAt: String?,
)

data class UsageSnapshot(
    val codex: CodexUsage,
    val openRouter: OpenRouterUsage,
    val meta: MetaUsage,
    val fetchedAt: String,
)

object UsageApi {
    fun fetch(): UsageSnapshot {
        if (BuildConfig.DEMO_MODE) return demoSnapshot()
        check(BuildConfig.USAGE_API_URL.isNotBlank()) {
            "Set USAGE_API_URL in local.properties before building"
        }

        val connection = (URL(BuildConfig.USAGE_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AI-Usage-Monitor/1.1")
        }

        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IllegalStateException("Server returned HTTP $responseCode")
            }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(body: String): UsageSnapshot {
        val root = JSONObject(body)
        val codexJson = root.getJSONObject("codex")
        val creditsJson = codexJson.optJSONObject("credits")
        val openRouterJson = root.getJSONObject("openrouter")
        val metaJson = root.getJSONObject("meta")

        return UsageSnapshot(
            codex = CodexUsage(
                ok = codexJson.optBoolean("ok", false),
                planType = codexJson.optString("plan_type", "unknown"),
                creditBalance = creditsJson?.optString("balance")?.toDoubleOrNull(),
                primaryWindow = codexJson.optJSONObject("primary_window")?.toUsageWindow(),
                secondaryWindow = codexJson.optJSONObject("secondary_window")?.toUsageWindow(),
                cloudMessages = creditsJson?.optJSONArray("approx_cloud_messages")?.let { array ->
                    if (array.length() >= 2) array.optInt(0)..array.optInt(1) else null
                },
                localMessages = creditsJson?.optJSONArray("approx_local_messages")?.let { array ->
                    if (array.length() >= 2) array.optInt(0)..array.optInt(1) else null
                },
            ),
            openRouter = OpenRouterUsage(
                ok = openRouterJson.optBoolean("ok", false),
                remaining = openRouterJson.optDouble("remaining", 0.0),
                totalCredits = openRouterJson.optDouble("total_credits", 0.0),
                totalUsage = openRouterJson.optDouble("total_usage", 0.0),
            ),
            meta = MetaUsage(
                balance = metaJson.optDouble("remaining_balance", metaJson.optDouble("balance", 0.0)),
                freeCredits = metaJson.optDouble(
                    "remaining_free_credits",
                    metaJson.optDouble("free_credits", 0.0),
                ),
                currency = metaJson.optString("currency", "USD"),
                updatedAt = metaJson.optString("updated_at").takeIf { it.isNotBlank() },
            ),
            fetchedAt = root.getString("fetched_at"),
        )
    }

    private fun JSONObject.toUsageWindow(): UsageWindow = UsageWindow(
        usedPercent = optInt("used_percent", 0).coerceIn(0, 100),
        resetAtEpochSeconds = optLong("reset_at", 0L),
    )

    private fun demoSnapshot(): UsageSnapshot {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return UsageSnapshot(
            codex = CodexUsage(
                ok = true,
                planType = "sample",
                creditBalance = 250.0,
                primaryWindow = UsageWindow(
                    usedPercent = 35,
                    resetAtEpochSeconds = now.plusHours(3).toEpochSecond(),
                ),
                secondaryWindow = UsageWindow(
                    usedPercent = 18,
                    resetAtEpochSeconds = now.plusDays(4).toEpochSecond(),
                ),
                cloudMessages = 10..50,
                localMessages = 75..300,
            ),
            openRouter = OpenRouterUsage(
                ok = true,
                remaining = 32.75,
                totalCredits = 100.0,
                totalUsage = 67.25,
            ),
            meta = MetaUsage(
                balance = 10.0,
                freeCredits = 25.0,
                currency = "USD",
                updatedAt = now.toString(),
            ),
            fetchedAt = now.toString(),
        )
    }
}
