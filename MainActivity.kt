package com.example.brainwaves

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object SessionStore { var session: String = "" }

private const val PREFS_NAME = "brainwaves_prefs"
private const val PREF_SESSION = "session_token"

data class TrackResult(val name: String, val artist: String, val uri: String)
data class Preset(val label: String, val query: String)

class MainActivity : ComponentActivity() {
    // Update if ngrok changes
    private val baseUrl = "https://supertragically-corollate-alia.ngrok-free.dev"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0A0C10),
                    surface = Color(0xFF0F1218),
                    primary = Color(0xFF2E6BFF),
                    secondary = Color(0xFF00D3A7),
                    tertiary = Color(0xFFFFB000),
                    onBackground = Color(0xFFE9ECF1),
                    onSurface = Color(0xFFE9ECF1),
                    onPrimary = Color(0xFF0A0C10)
                ),
                typography = Typography()
            ) {
                CDJ3000Screen(baseUrl = baseUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val session = intent?.data?.getQueryParameter("session")
        if (!session.isNullOrBlank()) SessionStore.session = session
    }
}

@Composable
fun CDJ3000Screen(baseUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, 0) }

    // ===== Session =====
    var session by remember { mutableStateOf("") }

    // ===== Playback state =====
    var nowPlaying by remember { mutableStateOf("Not connected") }
    var nowArtist by remember { mutableStateOf("") }
    var ticker by remember { mutableStateOf("Tap LINK to connect") }

    // ===== Search =====
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<TrackResult>()) }

    // ===== Position =====
    var durationMs by remember { mutableStateOf(0L) }
    var progressMs by remember { mutableStateOf(0L) }
    val progressRatio by remember(durationMs, progressMs) {
        derivedStateOf {
            if (durationMs <= 0L) 0f
            else (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
    }

    // ===== Presets =====
    val presets = remember {
        listOf(
            Preset("VIBE", "chill"),
            Preset("80s", "80s hits"),
            Preset("FOCUS", "lofi"),
            Preset("GYM", "workout"),
            Preset("HOUSE", "house music"),
            Preset("RAP", "hip hop"),
            Preset("INDIE", "indie"),
            Preset("POP", "pop hits")
        )
    }

    // ===== Browse knob =====
    var browseValue by remember { mutableStateOf(0.12f) }
    var lastAutoPresetIndex by remember { mutableStateOf(-1) }
    val presetIndex = remember(browseValue) {
        (browseValue * (presets.size - 1)).roundToInt().coerceIn(0, presets.size - 1)
    }

    // ===== Jog wheel =====
    var jogAngle by remember { mutableStateOf(0f) }
    var pendingJogMs by remember { mutableStateOf(0L) }
    var jogLabel by remember { mutableStateOf("0s") }

    val stepMs = 5000L
    val degreesPerStep = 16f
    val maxBurstSteps = 10

    // ===== Load saved session + deep link session =====
    LaunchedEffect(Unit) {
        val saved = prefs.getString(PREF_SESSION, "") ?: ""
        if (saved.isNotBlank()) {
            session = saved
            ticker = "Session loaded"
        }
        if (SessionStore.session.isNotBlank()) {
            session = SessionStore.session
            prefs.edit().putString(PREF_SESSION, session).apply()
            ticker = "Linked"
        }
    }

    // ===== Auto-search when preset changes =====
    LaunchedEffect(presetIndex) {
        if (presetIndex != lastAutoPresetIndex) {
            lastAutoPresetIndex = presetIndex
            val p = presets[presetIndex]
            query = p.query
            if (session.isNotBlank()) {
                ticker = "Loading ${p.label}…"
                searchTracks(baseUrl, session, p.query, scope, limit = 20) { ok, list, msg ->
                    if (ok) {
                        results = list
                        ticker = "Loaded: ${p.label}"
                    } else {
                        ticker = msg
                    }
                }
            }
        }
    }

    // ===== Jog wheel throttle =====
    LaunchedEffect(pendingJogMs, session) {
        if (session.isBlank()) return@LaunchedEffect
        if (pendingJogMs == 0L) return@LaunchedEffect
        delay(140)

        val delta = pendingJogMs
        pendingJogMs = 0L

        if (durationMs <= 0L) {
            ticker = "Press INFO first"
            return@LaunchedEffect
        }

        val target = (progressMs + delta).coerceIn(0L, (durationMs - 1L).coerceAtLeast(0L))
        val sign = if (delta >= 0) "+" else "-"
        ticker = "Jog $sign${abs(delta) / 1000}s"

        seekTo(baseUrl, session, target, scope) { ok ->
            ticker = if (ok) "Seek" else "Seek failed"
        }
        progressMs = target
    }

    // ===== Background =====
    val bg = Brush.verticalGradient(listOf(Color(0xFF07090D), Color(0xFF0A0C10), Color(0xFF06070A)))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TopStatusBarCompact(
            leftTitle = "BrainWaves",
            rightTag = if (session.isBlank()) "OFFLINE" else "LINKED",
            sub = "CDJ-3000 Console"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // LEFT: Deck + transport + NEW list underneath
            Column(
                modifier = Modifier.weight(1.1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeckDisplayWithLED(
                    presetLabel = presets[presetIndex].label,
                    station = "DECK A",
                    nowPlaying = nowPlaying,
                    nowArtist = nowArtist,
                    ticker = ticker,
                    progressRatio = progressRatio,
                    elapsed = formatMs(progressMs),
                    remain = if (durationMs > 0) "-${formatMs(durationMs - progressMs)}" else "--:--",
                    linked = session.isNotBlank(),
                    onInfo = onInfo@{
                        if (session.isBlank()) {
                            ticker = "Tap LINK first"
                            return@onInfo
                        }

                        refreshNowPlaying(baseUrl, session, scope) { ok, title, artist, msg ->
                            if (ok) {
                                nowPlaying = title
                                nowArtist = artist
                                ticker = "Info"
                            } else {
                                ticker = msg
                            }
                        }

                        refreshPosition(baseUrl, session, scope) { ok, p, d, msg ->
                            if (ok) {
                                progressMs = p
                                durationMs = d
                            } else {
                                ticker = msg
                            }
                        }
                    }
                )

                TransportRow(
                    enabled = session.isNotBlank(),
                    onLink = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl/login")))
                        ticker = "Opening Spotify login…"
                    },
                    onPlay = {
                        if (session.isBlank()) {
                            ticker = "Tap LINK first"
                            return@TransportRow
                        }
                        postSimple(baseUrl, "/spotify/play", """{"session":"$session"}""", scope) { ok ->
                            ticker = if (ok) "Playing" else "Play failed"
                        }
                        refreshNowPlaying(baseUrl, session, scope) { ok, t, a, msg ->
                            if (ok) { nowPlaying = t; nowArtist = a } else ticker = msg
                        }
                        refreshPosition(baseUrl, session, scope) { ok, p, d, msg ->
                            if (ok) { progressMs = p; durationMs = d } else ticker = msg
                        }
                    },
                    onPause = {
                        if (session.isBlank()) {
                            ticker = "Tap LINK first"
                            return@TransportRow
                        }
                        postSimple(baseUrl, "/spotify/pause", """{"session":"$session"}""", scope) { ok ->
                            ticker = if (ok) "Paused" else "Pause failed"
                        }
                    },
                    onNext = {
                        if (session.isBlank()) {
                            ticker = "Tap LINK first"
                            return@TransportRow
                        }
                        postSimple(baseUrl, "/spotify/next", """{"session":"$session"}""", scope) { ok ->
                            ticker = if (ok) "Skip" else "Skip failed"
                        }
                        refreshNowPlaying(baseUrl, session, scope) { ok, t, a, msg ->
                            if (ok) { nowPlaying = t; nowArtist = a } else ticker = msg
                        }
                        refreshPosition(baseUrl, session, scope) { ok, p, d, msg ->
                            if (ok) { progressMs = p; durationMs = d } else ticker = msg
                        }
                    }
                )

                LeftQueuePanel(
                    results = results,
                    onQueue = onQueueTap@{ t ->
                        if (session.isBlank()) {
                            ticker = "Tap LINK first"
                            return@onQueueTap
                        }
                        ticker = "Queuing…"
                        postSimple(
                            baseUrl,
                            "/spotify/queue",
                            """{"session":"$session","uri":"${t.uri}"}""",
                            scope
                        ) { ok ->
                            ticker = if (ok) "Queued: ${t.name}" else "Queue failed"
                        }
                    }
                )
            }

            // RIGHT: Browse + search + results+jog
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BrowsePanel(
                    presetLabel = presets[presetIndex].label,
                    presetIndex = presetIndex,
                    totalPresets = presets.size,
                    browseValue = browseValue,
                    onBrowseValueChange = { browseValue = it },
                    sessionLinked = session.isNotBlank()
                )

                SearchPanel(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        if (session.isBlank()) { ticker = "Tap LINK first"; return@SearchPanel }
                        if (query.isBlank()) { ticker = "Type a search"; return@SearchPanel }
                        ticker = "Searching…"
                        searchTracks(baseUrl, session, query, scope, limit = 20) { ok, list, msg ->
                            if (ok) {
                                results = list
                                ticker = if (list.isEmpty()) "No results" else "Results"
                            } else ticker = msg
                        }
                    },
                    onClear = {
                        results = emptyList()
                        ticker = "Cleared"
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    ResultsPanelCompact(
                        modifier = Modifier.weight(1f),
                        results = results,
                        maxHeight = 230.dp,
                        onQueue = { t ->
                            if (session.isBlank()) {
                                ticker = "Tap LINK first"
                                return@ResultsPanelCompact
                            }
                            ticker = "Queuing…"
                            postSimple(
                                baseUrl,
                                "/spotify/queue",
                                """{"session":"$session","uri":"${t.uri}"}""",
                                scope
                            ) { ok ->
                                ticker = if (ok) "Queued: ${t.name}" else "Queue failed"
                            }
                        }
                    )

                    JogWheelPanelCompact(
                        modifier = Modifier.width(190.dp),
                        enabled = session.isNotBlank(),
                        angle = jogAngle,
                        progressRatio = progressRatio,
                        jogLabel = jogLabel,
                        onAngleDelta = { deltaDeg ->
                            jogAngle += deltaDeg
                            val steps = (deltaDeg / degreesPerStep).roundToInt()
                                .coerceIn(-maxBurstSteps, maxBurstSteps)
                            if (steps != 0) {
                                val deltaMs = steps.toLong() * stepMs
                                pendingJogMs += deltaMs
                                val sec = abs(deltaMs) / 1000
                                val sign = if (deltaMs >= 0) "+" else "-"
                                jogLabel = "$sign${sec}s"
                            }
                        },
                        onRefreshPos = {
                            if (session.isBlank()) { ticker = "Tap LINK first"; return@JogWheelPanelCompact }
                            refreshPosition(baseUrl, session, scope) { ok, p, d, msg ->
                                if (ok) {
                                    progressMs = p
                                    durationMs = d
                                    ticker = "Position"
                                } else ticker = msg
                            }
                        }
                    )
                }
            }
        }
    }
}


private val Silver = Color(0xFFC9CFD8)
private val SilverDark = Color(0xFF7B8794)
private val PanelBorderBrush =
    Brush.linearGradient(listOf(Silver.copy(alpha = 0.35f), Color(0xFF1B2230)))

@Composable
private fun TopStatusBarCompact(leftTitle: String, rightTag: String, sub: String) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0F1218),
        shape = shape,
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .border(1.dp, Color(0xFF1B2230), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(leftTitle, fontWeight = FontWeight.Black, fontSize = 18.sp)
                androidx.compose.material3.Text(sub, color = Color(0xFF9AA6B2), fontSize = 11.sp)
            }
            TagPill(text = rightTag)
        }
    }
}

@Composable
private fun TagPill(text: String) {
    val pillShape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(pillShape)
            .background(Color(0xFF111826))
            .border(1.dp, Color(0xFF1F2A3A), pillShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        androidx.compose.material3.Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = if (text == "LINKED") Color(0xFF00D3A7) else Color(0xFFE9ECF1)
        )
    }
}

@Composable
private fun DeckDisplayWithLED(
    presetLabel: String,
    station: String,
    nowPlaying: String,
    nowArtist: String,
    ticker: String,
    progressRatio: Float,
    elapsed: String,
    remain: String,
    linked: Boolean,
    onInfo: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)

    val infinite = rememberInfiniteTransition(label = "led")
    val ledAlpha by infinite.animateFloat(
        initialValue = 0.15f,
        targetValue = if (linked) 0.75f else 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (linked) 520 else 780, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ledAlpha"
    )
    val ledColor = if (linked) Color(0xFF00D3A7) else Color(0xFFFFB000)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0F1218),
        shape = shape
    ) {
        Column(
            Modifier
                .border(1.dp, Color(0xFF1B2230), shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // LED strip
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(10) { idx ->
                    val localAlpha =
                        (ledAlpha * (0.75f + 0.25f * sin((idx + 1) * 0.7f))).coerceIn(0.08f, 0.95f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(ledColor.copy(alpha = localAlpha))
                            .border(1.dp, Color(0xFF1B2230), RoundedCornerShape(99.dp))
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text(
                        station,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = SilverDark
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Text(
                            presetLabel,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = Color(0xFF00D3A7)
                        )
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.Text(
                            "WAVEFORM",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = SilverDark
                        )
                    }
                }

                Button(
                    onClick = onInfo,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111C2B)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    border = BorderStroke(1.dp, PanelBorderBrush)
                ) {
                    androidx.compose.material3.Text("INFO", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            WaveformStrip(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                progress = progressRatio
            )

            androidx.compose.material3.Text(
                text = nowPlaying,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            androidx.compose.material3.Text(
                text = nowArtist,
                fontSize = 12.sp,
                color = SilverDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = Color(0xFF2E6BFF),
                    trackColor = Color(0xFF1A2230)
                )
                Row(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(elapsed, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = SilverDark)
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.Text(remain, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = SilverDark)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0B0F16))
                    .border(1.dp, PanelBorderBrush, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                androidx.compose.material3.Text(
                    text = "• $ticker",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFE9ECF1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LeftQueuePanel(
    results: List<TrackResult>,
    onQueue: (TrackResult) -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(color = Color(0xFF0F1218), shape = shape) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, PanelBorderBrush, shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(
                    "QUEUED / SEARCHED",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = SilverDark
                )
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Text(
                    "${results.size}",
                    fontFamily = FontFamily.Monospace,
                    color = SilverDark,
                    fontSize = 12.sp
                )
            }

            if (results.isEmpty()) {
                androidx.compose.material3.Text("No tracks yet. Use presets or search.", color = SilverDark, fontSize = 12.sp)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 190.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results) { t ->
                        TrackRowCDJ(
                            name = t.name,
                            artist = t.artist,
                            onQueue = { onQueue(t) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveformStrip(modifier: Modifier, progress: Float) {
    val bg = Brush.horizontalGradient(listOf(Color(0xFF0B0F16), Color(0xFF0D1320)))
    val line = Color(0xFF263145)
    val playhead = Color(0xFF00D3A7)
    val accent = Color(0xFF2E6BFF)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, PanelBorderBrush, RoundedCornerShape(14.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val mid = h / 2f

            for (i in 0..6) {
                val x = w * (i / 6f)
                drawLine(line.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }
            drawLine(line.copy(alpha = 0.5f), Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)

            val bars = 70
            val barW = w / bars
            for (i in 0 until bars) {
                val t = i / (bars - 1f)
                val amp = (0.15f + 0.85f * abs(sin(t * 3.7f * PI).toFloat()))
                val jitter = (0.6f + 0.4f * abs(sin((t * 19f + 0.2f) * PI).toFloat()))
                val a = amp * jitter
                val barH = (h * 0.42f) * a
                val x = i * barW + barW * 0.5f
                val color = if (t <= progress) accent.copy(alpha = 0.95f) else Color(0xFF2A3344)
                drawLine(color, Offset(x, mid - barH), Offset(x, mid + barH), strokeWidth = min(4f, barW * 0.7f))
            }

            val px = (w * progress).coerceIn(0f, w)
            drawLine(playhead, Offset(px, 0f), Offset(px, h), strokeWidth = 3f)
        }
    }
}

@Composable
private fun TransportRow(
    enabled: Boolean,
    onLink: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton(
            label = if (enabled) "LINKED" else "LINK",
            sub = "Spotify",
            color = if (enabled) Color(0xFF0F1B18) else Color(0xFF111C2B),
            accent = if (enabled) Color(0xFF00D3A7) else Color(0xFF2E6BFF),
            onClick = onLink,
            modifier = Modifier.weight(1f)
        )
        BigButton(
            label = "PLAY",
            sub = "Resume",
            color = Color(0xFF101827),
            accent = Color(0xFF2E6BFF),
            onClick = onPlay,
            modifier = Modifier.weight(1f)
        )
        BigButton(
            label = "PAUSE",
            sub = "Hold",
            color = Color(0xFF181014),
            accent = Color(0xFFFFB000),
            onClick = onPause,
            modifier = Modifier.weight(1f)
        )
        BigButton(
            label = "NEXT",
            sub = "Skip",
            color = Color(0xFF120F18),
            accent = Color(0xFFE9ECF1),
            onClick = onNext,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BigButton(
    label: String,
    sub: String,
    color: Color,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Button(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(12.dp),
        border = BorderStroke(1.dp, PanelBorderBrush)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            androidx.compose.material3.Text(label, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = accent)
            androidx.compose.material3.Text(sub, fontSize = 12.sp, color = SilverDark)
        }
    }
}

@Composable
private fun BrowsePanel(
    presetLabel: String,
    presetIndex: Int,
    totalPresets: Int,
    browseValue: Float,
    onBrowseValueChange: (Float) -> Unit,
    sessionLinked: Boolean
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(color = Color(0xFF0F1218), shape = shape) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, PanelBorderBrush, shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(
                    "BROWSE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = SilverDark
                )
                Spacer(Modifier.weight(1f))
                TagPill(text = if (sessionLinked) "READY" else "LOCKED")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text("PRESET", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = SilverDark)
                    androidx.compose.material3.Text(
                        presetLabel,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        color = Color(0xFF00D3A7)
                    )
                    androidx.compose.material3.Text(
                        "${presetIndex + 1} / $totalPresets",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = SilverDark
                    )
                }

                RotaryBrowseKnob(
                    value = browseValue,
                    onValueChange = onBrowseValueChange,
                    modifier = Modifier.size(108.dp)
                )
            }
        }
    }
}

@Composable
private fun RotaryBrowseKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val ring = Color(0xFF1F2A3A)
    val face = Brush.radialGradient(listOf(Color(0xFF1B2230), Color(0xFF0B0F16)))
    val accent = Color(0xFF2E6BFF)
    val angle = (-135f + value.coerceIn(0f, 1f) * 270f)

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(face)
            .border(2.dp, PanelBorderBrush, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consumeAllChanges()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val touch = change.position
                    val rad = atan2(touch.y - center.y, touch.x - center.x)
                    var deg = Math.toDegrees(rad.toDouble()).toFloat()
                    deg = deg.coerceIn(-135f, 135f)
                    val newValue = ((deg + 135f) / 270f).coerceIn(0f, 1f)
                    onValueChange(newValue)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(color = ring, radius = r, style = Stroke(width = 2f))

            val ticks = 36
            for (i in 0 until ticks) {
                val t = i / ticks.toFloat()
                val a = (-135f + t * 270f) * (PI.toFloat() / 180f)
                val inner = r * 0.78f
                val outer = r * 0.92f
                val p1 = Offset(center.x + cos(a) * inner, center.y + sin(a) * inner)
                val p2 = Offset(center.x + cos(a) * outer, center.y + sin(a) * outer)
                drawLine(
                    color = if (t <= value) accent else ring.copy(alpha = 0.6f),
                    start = p1,
                    end = p2,
                    strokeWidth = 2f
                )
            }
        }

        Box(
            modifier = Modifier
                .size(8.dp, 36.dp)
                .rotate(angle)
                .offset(y = (-18).dp)
                .clip(RoundedCornerShape(6.dp))
                .background(accent)
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0xFF0A0C10))
                .border(1.dp, ring, CircleShape)
        )
    }
}

@Composable
private fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(color = Color(0xFF0F1218), shape = shape) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, PanelBorderBrush, shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            androidx.compose.material3.Text(
                "SEARCH",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = SilverDark
            )

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { androidx.compose.material3.Text("Artist / Track…", color = Color(0xFF6E7B88)) },
                textStyle = TextStyle(color = Color(0xFFE9ECF1), fontSize = 16.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1F2A3A),
                    unfocusedBorderColor = Color(0xFF1F2A3A),
                    cursorColor = Color(0xFF2E6BFF),
                    focusedTextColor = Color(0xFFE9ECF1),
                    unfocusedTextColor = Color(0xFFE9ECF1)
                )
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSearch,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111C2B)),
                    border = BorderStroke(1.dp, PanelBorderBrush)
                ) {
                    androidx.compose.material3.Text("SEARCH", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B0F16)),
                    border = BorderStroke(1.dp, PanelBorderBrush)
                ) {
                    androidx.compose.material3.Text("CLEAR", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ResultsPanelCompact(
    modifier: Modifier,
    results: List<TrackResult>,
    maxHeight: Dp,
    onQueue: (TrackResult) -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(color = Color(0xFF0F1218), shape = shape, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, PanelBorderBrush, shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(
                    "TRACK LIST",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = SilverDark
                )
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Text(
                    "${results.size}",
                    fontFamily = FontFamily.Monospace,
                    color = SilverDark,
                    fontSize = 12.sp
                )
            }

            if (results.isEmpty()) {
                androidx.compose.material3.Text("No results yet.", color = SilverDark, fontSize = 12.sp)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results) { t ->
                        TrackRowCDJ(name = t.name, artist = t.artist, onQueue = { onQueue(t) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRowCDJ(name: String, artist: String, onQueue: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = Color(0xFF0B0F16),
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PanelBorderBrush, shape)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.material3.Text(artist, color = SilverDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = onQueue,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F1B18)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                border = BorderStroke(1.dp, PanelBorderBrush)
            ) {
                androidx.compose.material3.Text(
                    "QUEUE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00D3A7)
                )
            }
        }
    }
}

@Composable
private fun JogWheelPanelCompact(
    modifier: Modifier,
    enabled: Boolean,
    angle: Float,
    progressRatio: Float,
    jogLabel: String,
    onAngleDelta: (Float) -> Unit,
    onRefreshPos: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(color = Color(0xFF0F1218), shape = shape, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, PanelBorderBrush, shape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text("JOG", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = SilverDark)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onRefreshPos,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111C2B)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, PanelBorderBrush)
                ) {
                    androidx.compose.material3.Text("POS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            CDJJogWheel(
                enabled = enabled,
                angle = angle,
                progressRatio = progressRatio,
                onAngleDelta = onAngleDelta,
                modifier = Modifier.size(160.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0B0F16))
                    .border(1.dp, PanelBorderBrush, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    jogLabel,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = Color(0xFFFFB000)
                )
            }

            androidx.compose.material3.Text(if (enabled) "Spin to seek" else "Link to enable", color = SilverDark, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CDJJogWheel(
    enabled: Boolean,
    angle: Float,
    progressRatio: Float,
    onAngleDelta: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val ring = Color(0xFF1F2A3A)
    val face = Brush.radialGradient(listOf(Color(0xFF1B2230), Color(0xFF0B0F16)))
    val accent = if (enabled) Color(0xFF2E6BFF) else Color(0xFF2A3344)
    val playhead = Color(0xFF00D3A7)

    var lastTouchAngle by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(face)
            .border(2.dp, PanelBorderBrush, CircleShape)
            .pointerInput(enabled) {
                detectDragGestures(
                    onDragStart = { lastTouchAngle = null },
                    onDragEnd = { lastTouchAngle = null }
                ) { change, _ ->
                    if (!enabled) return@detectDragGestures
                    change.consumeAllChanges()

                    val center = Offset(size.width / 2f, size.height / 2f)
                    val touch = change.position
                    val rad = atan2(touch.y - center.y, touch.x - center.x)
                    val deg = Math.toDegrees(rad.toDouble()).toFloat()

                    val prev = lastTouchAngle
                    if (prev != null) {
                        var delta = deg - prev
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        onAngleDelta(delta)
                    }
                    lastTouchAngle = deg
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c = Offset(w / 2f, h / 2f)
            val r = min(w, h) / 2f

            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progressRatio,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.86f, c.y - r * 0.86f),
                size = Size(r * 1.72f, r * 1.72f),
                style = Stroke(width = 10f)
            )

            val phAngle = (-90f + 360f * progressRatio) * (PI.toFloat() / 180f)
            val inner = r * 0.62f
            val outer = r * 0.92f
            val p1 = Offset(c.x + cos(phAngle) * inner, c.y + sin(phAngle) * inner)
            val p2 = Offset(c.x + cos(phAngle) * outer, c.y + sin(phAngle) * outer)
            drawLine(playhead, p1, p2, strokeWidth = 4f)

            val grooves = 8
            for (i in 1..grooves) {
                val gr = r * (0.18f + i * 0.08f)
                drawCircle(
                    color = ring.copy(alpha = 0.35f),
                    radius = gr,
                    center = c,
                    style = Stroke(width = 1.2f)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(8.dp, 52.dp)
                .rotate(angle)
                .offset(y = (-24).dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFFFB000))
        )

        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color(0xFF0A0C10))
                .border(1.dp, ring, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text("JOG", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, color = SilverDark)
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

private fun postJson(
    baseUrl: String,
    endpoint: String,
    json: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: (String) -> Unit
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl$endpoint")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(json.toByteArray()) }

                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val text = stream.bufferedReader().readText()
                conn.disconnect()
                text
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }
        onDone(result)
    }
}

private fun postSimple(
    baseUrl: String,
    endpoint: String,
    json: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onOk: (Boolean) -> Unit
) {
    postJson(baseUrl, endpoint, json, scope) { raw ->
        onOk(!raw.startsWith("ERROR:") && !raw.contains("Token refresh failed", ignoreCase = true))
    }
}

private fun refreshNowPlaying(
    baseUrl: String,
    session: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onResult: (ok: Boolean, title: String, artist: String, msg: String) -> Unit
) {
    if (session.isBlank()) {
        onResult(false, "", "", "Tap LINK first")
        return
    }
    postJson(baseUrl, "/spotify/current", """{"session":"$session"}""", scope) { raw ->
        if (raw.startsWith("ERROR:")) {
            onResult(false, "", "", "Info failed")
            return@postJson
        }
        val track = Regex(""""track"\s*:\s*"([^"]*)"""").find(raw)?.groupValues?.getOrNull(1)
        val artist = Regex(""""artist"\s*:\s*"([^"]*)"""").find(raw)?.groupValues?.getOrNull(1) ?: ""
        if (track.isNullOrBlank()) onResult(false, "", "", "No track info")
        else onResult(true, track, artist, "OK")
    }
}

private fun refreshPosition(
    baseUrl: String,
    session: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onResult: (ok: Boolean, progressMs: Long, durationMs: Long, msg: String) -> Unit
) {
    if (session.isBlank()) {
        onResult(false, 0, 0, "Tap LINK first")
        return
    }
    postJson(baseUrl, "/spotify/position", """{"session":"$session"}""", scope) { raw ->
        if (raw.startsWith("ERROR:")) {
            onResult(false, 0, 0, "Position failed")
            return@postJson
        }
        val p = Regex(""""progress_ms"\s*:\s*(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val d = Regex(""""duration_ms"\s*:\s*(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (p != null && d != null && d > 0L) onResult(true, p, d, "OK")
        else onResult(false, 0, 0, "No device / no track")
    }
}

private fun seekTo(
    baseUrl: String,
    session: String,
    positionMs: Long,
    scope: kotlinx.coroutines.CoroutineScope,
    onOk: (Boolean) -> Unit
) {
    if (session.isBlank()) { onOk(false); return }
    postJson(baseUrl, "/spotify/seek", """{"session":"$session","position_ms":$positionMs}""", scope) { raw ->
        onOk(!raw.startsWith("ERROR:"))
    }
}

private fun searchTracks(
    baseUrl: String,
    session: String,
    q: String,
    scope: kotlinx.coroutines.CoroutineScope,
    limit: Int = 20,
    onResult: (ok: Boolean, list: List<TrackResult>, msg: String) -> Unit
) {
    if (session.isBlank()) {
        onResult(false, emptyList(), "Tap LINK first")
        return
    }
    if (q.isBlank()) {
        onResult(false, emptyList(), "Type a search")
        return
    }

    val cleanQ = q.replace("\"", "")
    postJson(baseUrl, "/spotify/search", """{"session":"$session","q":"$cleanQ"}""", scope) { raw ->
        if (raw.startsWith("ERROR:")) {
            onResult(false, emptyList(), "Search failed")
            return@postJson
        }

        val items = mutableListOf<TrackResult>()
        val uriRegex = Regex(""""uri"\s*:\s*"([^"]*spotify:track:[^"]*)"""")
        val nameRegex = Regex(""""name"\s*:\s*"([^"]*)"""")
        val artistRegex = Regex(""""artist"\s*:\s*"([^"]*)"""")

        val chunks = raw.split("\"uri\"")
        for (c in chunks) {
            val uri = uriRegex.find("\"uri\"$c")?.groupValues?.getOrNull(1) ?: continue
            val name = nameRegex.find(c)?.groupValues?.getOrNull(1) ?: "Unknown"
            val artist = artistRegex.find(c)?.groupValues?.getOrNull(1) ?: ""
            items.add(TrackResult(name, artist, uri))
            if (items.size >= limit) break
        }

        onResult(true, items, "OK")
    }
}
