package com.carlren.aiusagemonitor

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Background = Color(0xFF05070B)
private val Surface = Color(0xFF111722)
private val SurfaceRaised = Color(0xFF202938)
private val Track = Color(0xFF303B4D)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFD2DAE7)
private val CodexBlue = Color(0xFF87A5FF)
private val RouterCyan = Color(0xFF62E0FF)
private val MetaGreen = Color(0xFF70E0AD)
private val Warning = Color(0xFFFFC857)
private val Danger = Color(0xFFFF7B8B)

private const val REFRESH_INTERVAL_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = CodexBlue,
                    background = Background,
                    surface = Surface,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary,
                ),
            ) {
                UsageMonitorApp()
            }
        }
        window.decorView.post { enterImmersiveMode() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}

@Composable
private fun UsageMonitorApp() {
    var snapshot by remember { mutableStateOf<UsageSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        if (loading) return
        loading = true
        try {
            snapshot = withContext(Dispatchers.IO) { UsageApi.fetch() }
            error = null
        } catch (failure: Exception) {
            error = failure.message ?: "Unable to reach the usage service"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refresh()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            DashboardHeader(
                snapshot = snapshot,
                loading = loading,
                onRefresh = { scope.launch { refresh() } },
            )
            Spacer(Modifier.height(10.dp))
            if (snapshot == null) {
                LoadingPanel(error = error, loading = loading)
            } else {
                Dashboard(snapshot = snapshot!!, modifier = Modifier.weight(1f))
            }
        }

        if (error != null && snapshot != null) {
            ErrorBanner(
                message = "Refresh failed — showing last update",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun DashboardHeader(
    snapshot: UsageSnapshot?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AI USAGE",
                    color = TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (snapshot != null) MetaGreen else Warning),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = when {
                        BuildConfig.DEMO_MODE -> "DEMO DATA"
                        snapshot != null -> "LIVE"
                        else -> "CONNECTING"
                    },
                    color = if (BuildConfig.DEMO_MODE) Warning else if (snapshot != null) MetaGreen else Warning,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            snapshot?.let {
                Text(
                    text = "Updated ${formatIsoTime(it.fetchedAt)}  •  refreshes every minute",
                    color = TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Button(
            onClick = onRefresh,
            enabled = !loading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceRaised,
                contentColor = TextPrimary,
                disabledContainerColor = SurfaceRaised,
                disabledContentColor = TextSecondary,
            ),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 13.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = CodexBlue,
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = if (loading) "Updating" else "Refresh",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Dashboard(snapshot: UsageSnapshot, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CodexCard(snapshot.codex, Modifier.weight(1.55f))
        OpenRouterCard(snapshot.openRouter, Modifier.weight(1f))
        MetaCard(snapshot.meta, Modifier.weight(1f))
    }
}

@Composable
private fun CodexCard(codex: CodexUsage, modifier: Modifier = Modifier) {
    ProviderCard(
        title = "Codex",
        status = codex.planType.uppercase(),
        accent = CodexBlue,
        healthy = codex.ok,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = codex.creditBalance?.let(::formatCredits) ?: "—",
                    color = TextPrimary,
                    fontSize = 44.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                )
                Text(
                    text = "CREDITS AVAILABLE",
                    color = TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                codex.cloudMessages?.let { MessageEstimate("${it.first}–${it.last}", "CLOUD") }
                Spacer(Modifier.height(10.dp))
                codex.localMessages?.let { MessageEstimate("${it.first}–${it.last}", "LOCAL") }
            }
        }
        Spacer(Modifier.height(24.dp))
        codex.primaryWindow?.let { UsageBar("5-HOUR WINDOW", it) }
        Spacer(Modifier.height(22.dp))
        codex.secondaryWindow?.let { UsageBar("WEEKLY WINDOW", it) }
    }
}

@Composable
private fun MessageEstimate(value: String, label: String) {
    Text(
        text = value,
        color = TextPrimary,
        fontSize = 21.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "$label MESSAGES",
        color = TextSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun OpenRouterCard(usage: OpenRouterUsage, modifier: Modifier = Modifier) {
    val remainingPercent = if (usage.totalCredits > 0) {
        ((usage.remaining / usage.totalCredits) * 100).toInt().coerceIn(0, 100)
    } else 0
    val statusColor = remainingColor(remainingPercent)

    ProviderCard(
        title = "OpenRouter",
        status = if (usage.ok) "LIVE" else "ISSUE",
        accent = RouterCyan,
        healthy = usage.ok,
        modifier = modifier,
    ) {
        Text(
            text = formatCurrency(usage.remaining),
            color = TextPrimary,
            fontSize = 43.sp,
            lineHeight = 45.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        )
        Text(
            text = "REMAINING",
            color = TextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(28.dp))
        StatRow("Used", formatCurrency(usage.totalUsage))
        Spacer(Modifier.height(14.dp))
        StatRow("Starting total", formatCurrency(usage.totalCredits))
        Spacer(Modifier.height(28.dp))
        Text(
            text = "$remainingPercent% LEFT",
            color = statusColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        HighContrastProgress(remainingPercent / 100f, statusColor)
    }
}

@Composable
private fun MetaCard(usage: MetaUsage, modifier: Modifier = Modifier) {
    val totalAvailable = usage.balance + usage.freeCredits
    ProviderCard(
        title = "Meta",
        status = "SYNCED",
        accent = MetaGreen,
        healthy = true,
        modifier = modifier,
    ) {
        Text(
            text = formatCurrency(totalAvailable),
            color = TextPrimary,
            fontSize = 43.sp,
            lineHeight = 45.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
        )
        Text(
            text = "TOTAL AVAILABLE",
            color = TextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(28.dp))
        StatRow("Free credits", formatCurrency(usage.freeCredits))
        Spacer(Modifier.height(14.dp))
        StatRow("Balance", formatCurrency(usage.balance))
        Spacer(Modifier.height(32.dp))
        Text(
            text = "LAST META SYNC",
            color = TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = usage.updatedAt?.let(::formatIsoTime) ?: "Not available",
            color = MetaGreen,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ProviderCard(
    title: String,
    status: String,
    accent: Color,
    healthy: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(2.dp, accent.copy(alpha = 0.78f)),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent),
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = status,
                    color = if (healthy) accent else Danger,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(24.dp))
            content()
        }
    }
}

@Composable
private fun UsageBar(label: String, window: UsageWindow) {
    val remaining = (100 - window.usedPercent).coerceIn(0, 100)
    val color = remainingColor(remaining)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Text(label, color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Resets ${formatEpochTime(window.resetAtEpochSeconds)}",
                color = TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = "$remaining% LEFT",
            color = color,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    Spacer(Modifier.height(9.dp))
    HighContrastProgress(remaining / 100f, color)
}

@Composable
private fun HighContrastProgress(progress: Float, color: Color) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(20.dp)),
        color = color,
        trackColor = Track,
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(value, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LoadingPanel(error: String?, loading: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(52.dp),
                    color = CodexBlue,
                    strokeWidth = 5.dp,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Fetching fresh usage…",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else if (error != null) {
                Text(
                    text = "Could not load usage. Tap Refresh to try again.",
                    color = Danger,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, Danger),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B121B)),
    ) {
        Text(
            text = message,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

private fun remainingColor(percent: Int): Color = when {
    percent <= 15 -> Danger
    percent <= 30 -> Warning
    else -> MetaGreen
}

private fun formatCredits(value: Double): String =
    NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)

private fun formatCurrency(value: Double): String =
    NumberFormat.getCurrencyInstance(Locale.US).format(value)

private fun formatEpochTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "—"
    return DateTimeFormatter.ofPattern("EEE h:mm a")
        .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
}

private fun formatIsoTime(value: String): String = try {
    DateTimeFormatter.ofPattern("h:mm a")
        .format(OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()))
} catch (_: Exception) {
    value
}
