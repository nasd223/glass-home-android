package com.glasshome.tv

import android.os.SystemClock
import android.text.format.DateFormat
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasshome.tv.web.PhotoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

// Sizes follow the Samsung version's CSS at half scale: a 1080p TV is 960 x 540 dp.
private val GlassTint = Color(0x6B1C1C1E)
private val GlassStrong = Color(0xD91C1C1E)
private val GlassBorder = Color(0x1AFFFFFF)
private val Text2 = Color(0xB8FFFFFF)
private val Mint = Color(0xFFC4F4EF)
private val TextShadow = Shadow(Color(0x59000000), Offset(0f, 2f), 22f)

/** The phone website's address and password, for the + panel. */
class Phone(val url: String?, val password: String?, val error: String?)

/** What the home screen shows. The activity owns it; the UI reads and changes it. */
class HomeState(private val apps: Apps, private val service: PhotoService) {
    var resumed by mutableStateOf(false)
    var ambient by mutableStateOf(false)
    var installed by mutableStateOf<Map<String, TvApp>>(emptyMap())
    var dockIds by mutableStateOf<List<String>>(emptyList())
    /** Website and YouTube shortcuts from the phone; they sit after the apps. */
    var shortcuts by mutableStateOf<List<TvApp>>(emptyList())
    var phone by mutableStateOf<Phone?>(null)
    var menuFor by mutableStateOf<String?>(null)
    var addOpen by mutableStateOf(false)
    /** The quick settings panel, from the gear by the clock. */
    var settingsOpen by mutableStateOf(false)
    var quickSettings by mutableStateOf<List<TvApp>>(emptyList())
    /** The weather location panel, from the weather under the clock. */
    var weatherOpen by mutableStateOf(false)
    var focusedId by mutableStateOf<String?>(null)
    var focusedCenterX by mutableFloatStateOf(0f)

    /** Set focusTarget and bump focusTick to move focus to a dock tile (or the gear). */
    var focusTarget: String? = null
    val requesters = mutableMapOf<String, FocusRequester>()
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    var focusTick by mutableIntStateOf(0)

    @Volatile var lastInput = SystemClock.uptimeMillis()

    val dock: List<TvApp> get() = dockIds.mapNotNull { installed[it] } + shortcuts
    val overlayOpen: Boolean get() = menuFor != null || addOpen || settingsOpen || weatherOpen

    fun focus(id: String?) {
        focusTarget = id ?: dock.firstOrNull()?.id ?: PLUS
        focusTick++
    }

    fun goHome() {
        menuFor = null
        addOpen = false
        settingsOpen = false
        weatherOpen = false
        ambient = false
        lastInput = SystemClock.uptimeMillis()
        focus(dock.firstOrNull()?.id)
    }

    /** Apps move among apps and shortcuts among shortcuts. */
    fun canMove(id: String, delta: Int): Boolean {
        val ids = dock.map { it.id }
        val j = ids.indexOf(id) + delta
        return ids.indexOf(id) >= 0 && j in ids.indices && Shortcuts.isShortcut(ids[j]) == Shortcuts.isShortcut(id)
    }

    fun move(id: String, delta: Int) {
        if (!canMove(id, delta)) return
        if (Shortcuts.isShortcut(id)) {
            val list = shortcuts.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            list[i] = list[i + delta].also { list[i + delta] = list[i] }
            shortcuts = list
            val order = list.map { Shortcuts.serviceId(it.id) }
            Thread { service.reorderShortcuts(order) }.start()
        } else {
            val visible = appIds().toMutableList()
            val i = visible.indexOf(id)
            visible[i] = visible[i + delta].also { visible[i + delta] = visible[i] }
            save(visible)
        }
        focus(id)
    }

    fun remove(id: String) {
        val visible = dock.map { it.id }
        val i = visible.indexOf(id)
        if (Shortcuts.isShortcut(id)) {
            shortcuts = shortcuts.filter { it.id != id }
            Thread { service.removeShortcut(Shortcuts.serviceId(id)) }.start()
        } else {
            save(appIds() - id)
        }
        focus(visible.getOrNull(i + 1) ?: visible.getOrNull(i - 1))
    }

    fun add(id: String) {
        save(appIds() + id)
        focus(id)
    }

    /** A new generated password for the phone website; every phone is signed out. */
    fun newPassword() {
        Thread { service.newPassword() }.start()
    }

    private fun appIds() = dockIds.filter { it in installed }

    private fun save(visible: List<String>) {
        // Uninstalled apps keep their place at the end, so they return if reinstalled.
        dockIds = visible + dockIds.filter { it !in installed && it !in visible }
        apps.saveDock(dockIds)
    }

    companion object {
        const val PLUS = "@plus"
        const val GEAR = "@gear"
        const val WEATHER = "@weather"
    }
}

class GlassRect(val rect: Rect, val radius: Float) {
    override fun equals(other: Any?) = other is GlassRect && other.rect == rect && other.radius == radius
    override fun hashCode() = rect.hashCode() * 31 + radius.hashCode()
}

@Composable
fun HomeScreen(state: HomeState, photos: PhotoLibrary, weather: WeatherSource, onLaunch: (TvApp) -> Unit) {
    val glass = remember { mutableStateMapOf<String, GlassRect>() }
    var caption by remember { mutableStateOf("") }
    val chrome by animateFloatAsState(if (state.ambient) 0f else 1f, tween(900), label = "chrome")
    val night = rememberNight()

    // Back does nothing on the home screen; it only closes the menu and panel.
    BackHandler(enabled = !state.overlayOpen) {}

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val idle = SystemClock.uptimeMillis() - state.lastInput
            if (Config.AMBIENT_ENABLED && state.resumed && !state.overlayOpen && idle > Config.AMBIENT_AFTER_MS) {
                state.ambient = true
            }
        }
    }

    val dim by animateFloatAsState(if (night) 0.38f else 0f, tween(3000), label = "night")

    Box(Modifier.fillMaxSize()) {
        Backdrop(photos, state.resumed, glass, { chrome }, { dim }, { caption = it })

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = chrome }.padding(horizontal = 48.dp, vertical = 27.dp)) {
            Brand(Modifier.align(Alignment.TopStart).cached())
            Column(Modifier.align(Alignment.TopEnd).cached(), horizontalAlignment = Alignment.End) {
                Row {
                    if (state.quickSettings.isNotEmpty()) {
                        GearButton(state, Modifier.padding(top = 9.dp))
                        Spacer(Modifier.width(16.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) { Clock() }
                }
                WeatherView(state, weather, glass)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Dock(state, glass, onLaunch)
                FocusedLabel(state)
                Spacer(Modifier.height(18.dp))
            }
            Label(caption, 9.sp, Modifier.align(Alignment.BottomStart).cached(), color = Text2)
        }

        state.menuFor?.let { id -> state.dock.firstOrNull { it.id == id }?.let { TileMenu(state, it, glass) } }
        if (state.addOpen) AddPanel(state, glass)
        if (state.settingsOpen) QuickSettingsPanel(state, glass, onLaunch)
        if (state.weatherOpen) WeatherPanel(state, weather, glass)
    }
}

// ---------- backdrop ----------

private class Slide(val photo: Photo, var start: Long, val drift: Int)

private val Drifts = listOf(Offset(-1f, 0f), Offset(1f, -0.5f), Offset(0f, 1f), Offset(1f, 0.5f))

/** What the backdrop draws with, made once rather than every frame. */
private class Paints {
    val matrix = android.graphics.Matrix()
    val photo = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    val glass = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var dim = -1f

    /** Evening dimming as a colour filter on the photos: no extra full-screen layer to draw. */
    fun dim(d: Float) {
        if (d == dim) return
        dim = d
        val k = 1f - d
        val darken = android.graphics.ColorMatrix().apply { setScale(k, k, k, 1f) }
        photo.colorFilter = if (d > 0f) android.graphics.ColorMatrixColorFilter(darken) else null
        val saturate = android.graphics.ColorMatrix().apply { setSaturation(1.6f); postConcat(darken) }
        glass.colorFilter = android.graphics.ColorMatrixColorFilter(saturate)
    }
}

@Composable
private fun Backdrop(
    photos: PhotoLibrary,
    active: Boolean,
    glass: Map<String, GlassRect>,
    chrome: () -> Float,
    dim: () -> Float,
    onCaption: (String) -> Unit,
) {
    val context = LocalContext.current
    var cur by remember { mutableStateOf<Slide?>(null) }
    var prev by remember { mutableStateOf<Slide?>(null) }
    val fade = remember { Animatable(1f) }
    val frame = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val inFront by rememberUpdatedState(active)
    val paints = remember { Paints() }

    LaunchedEffect(Unit) {
        val dm = context.resources.displayMetrics
        var i = 0
        while (true) {
            snapshotFlow { inFront }.first { it }
            val photo = withContext(Dispatchers.IO) {
                runCatching { photos.load(photos.next(), dm.widthPixels, dm.heightPixels) }.getOrNull()
            }
            if (photo == null) {
                i++
                delay(1_000)
                continue
            }
            // Fade to 0 before the swap, so no frame shows the new photo at full strength.
            if (cur != null) fade.snapTo(0f)
            prev = cur
            cur = Slide(photo, SystemClock.uptimeMillis(), i % Drifts.size)
            onCaption(photo.caption)
            if (prev != null) {
                fade.animateTo(1f, tween(Config.FADE_MS, easing = LinearEasing))
                prev = null
            }
            // Only time in front counts: after an hour of Netflix the photo
            // carries on where it was instead of changing the moment you return.
            var shown = SystemClock.uptimeMillis() - cur!!.start
            var last = SystemClock.uptimeMillis()
            while (shown < Config.PHOTO_INTERVAL_MS - Config.FADE_MS) {
                delay(250)
                val now = SystemClock.uptimeMillis()
                if (inFront) shown += now - last else cur!!.start += now - last
                last = now
            }
            i++
        }
    }
    // Redraw every frame for the slow zoom, only while the home screen is in front.
    // The zoom follows the frame's own time (the display's vsync, on the same clock
    // as uptimeMillis), not the moment the frame happens to be drawn, so it moves
    // the same distance every frame instead of stuttering. (Every other frame was
    // tried for the Chromecast HD, which drops about one frame in five even on a
    // blank screen: it made both Chromecasts less even, not more.)
    LaunchedEffect(active) {
        frame.longValue = SystemClock.uptimeMillis()
        if (active) while (true) withFrameMillis { frame.longValue = it }
    }

    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val shade = shading(size, this)
            onDrawBehind {
                val now = frame.longValue
                val p = prev
                val c = cur
                val f = if (p != null) fade.value else 1f
                paints.dim(dim())
                val canvas = drawContext.canvas.nativeCanvas
                if (c == null) drawRect(Color.Black)
                p?.let { drawSlide(canvas, it, now, 1f, paints) }
                c?.let { drawSlide(canvas, it, now, f, paints) }

                // Frosted glass: the blurred photo, drawn in each glass panel's shape.
                val a = chrome()
                if (a > 0f) for (g in glass.values) {
                    p?.let { drawGlass(canvas, it, g, now, a, paints) }
                    c?.let { drawGlass(canvas, it, g, now, a * f, paints) }
                }

                drawImage(shade, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
        },
    )
}

/**
 * The shade behind the dock and the clock that keeps text readable on bright
 * photos, drawn once at a quarter of the screen's size: it's all soft gradients,
 * so it looks the same scaled up. Drawn live, the two radial gradients alone
 * took a Chromecast's GPU about 40 ms a frame.
 */
private fun shading(size: Size, density: androidx.compose.ui.unit.Density): ImageBitmap {
    val w = size.width.coerceAtLeast(4f)
    val h = size.height.coerceAtLeast(4f)
    val img = ImageBitmap((w / 4).toInt().coerceAtLeast(1), (h / 4).toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(img.width.toFloat(), img.height.toFloat())) {
        scale(img.width / w, img.height / h, Offset.Zero) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.28f to Color(0x1A000000), 0.62f to Color(0x57000000), 1f to Color(0x99000000),
                    startY = h * 0.45f, endY = h,
                ),
                topLeft = Offset(0f, h * 0.45f), size = Size(w, h * 0.55f),
            )
            drawRect(Brush.radialGradient(0f to Color(0x85000000), 0.45f to Color(0x42000000), 1f to Color.Transparent, center = Offset(w, 0f), radius = w * 0.55f), size = Size(w, h))
            drawRect(Brush.radialGradient(0f to Color(0x6B000000), 0.45f to Color(0x33000000), 1f to Color.Transparent, center = Offset.Zero, radius = w * 0.4f), size = Size(w, h))
        }
    }
    return img
}

/**
 * Sets [m] to place an image [imgW] wide covering the screen, zoomed in slowly
 * and drifting over its time on screen.
 */
private fun DrawScope.place(s: Slide, imgW: Int, imgH: Int, now: Long, m: android.graphics.Matrix) {
    val life = (Config.PHOTO_INTERVAL_MS + Config.FADE_MS).toFloat()
    val t = ((now - s.start) / life).coerceIn(0f, 1f)
    val scale = max(size.width / imgW, size.height / imgH) * (1f + (Config.KEN_BURNS_SCALE - 1f) * t)
    val extraX = imgW * scale - size.width
    val extraY = imgH * scale - size.height
    val d = Drifts[s.drift]
    m.setScale(scale, scale)
    m.postTranslate(-extraX / 2 + d.x * extraX * 0.4f * (2 * t - 1), -extraY / 2 + d.y * extraY * 0.4f * (2 * t - 1))
}

private fun DrawScope.drawSlide(canvas: android.graphics.Canvas, s: Slide, now: Long, alpha: Float, paints: Paints) {
    val img = s.photo.sharp
    place(s, img.width, img.height, now, paints.matrix)
    paints.photo.alpha = (alpha * 255).toInt()
    canvas.drawBitmap(img, paints.matrix, paints.photo)
}

private fun DrawScope.drawGlass(canvas: android.graphics.Canvas, s: Slide, g: GlassRect, now: Long, alpha: Float, paints: Paints) {
    val img = s.photo.soft
    place(s, img.width, img.height, now, paints.matrix)
    s.photo.softShader.setLocalMatrix(paints.matrix)
    paints.glass.shader = s.photo.softShader
    paints.glass.alpha = (alpha * 255).toInt()
    canvas.drawRoundRect(g.rect.left, g.rect.top, g.rect.right, g.rect.bottom, g.radius, g.radius, paints.glass)
}

/**
 * Keeps this part as a texture that's only redrawn when it changes (the clock
 * each second, a focus move). Otherwise each label's blurred shadow is drawn
 * again on every frame of the photos' slow zoom.
 */
private fun Modifier.cached() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

/** Marks a panel as frosted glass: the backdrop draws the blurred photo behind it. */
private fun Modifier.glass(id: String, glass: MutableMap<String, GlassRect>, radius: Dp, tint: Color = GlassTint) = composed {
    val r = with(LocalDensity.current) { radius.toPx() }
    DisposableEffect(id) { onDispose { glass.remove(id) } }
    val shape = RoundedCornerShape(radius)
    onGloballyPositioned {
        val g = GlassRect(it.boundsInRoot(), r)
        if (glass[id] != g) glass[id] = g
    }
        .clip(shape)
        .background(tint)
        .background(Brush.verticalGradient(0f to Color(0x0FFFFFFF), 0.4f to Color.Transparent))
        .border(1.dp, GlassBorder, shape)
}

// ---------- top: brand, clock, weather ----------

@Composable
private fun Brand(modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(40.dp, 30.dp)) {
            // app/img/brand-glyph.svg from the Samsung version, viewBox 660 x 500.
            val s = size.width / 660f
            drawRoundRect(Color(0x1AFFFFFF), Offset(20 * s, 17 * s), Size(620 * s, 410 * s), CornerRadius(70 * s))
            drawRoundRect(Mint, Offset(20 * s, 17 * s), Size(620 * s, 410 * s), CornerRadius(70 * s), style = Stroke(34 * s))
            drawRoundRect(Mint.copy(alpha = 0.9f), Offset(150 * s, 302 * s), Size(360 * s, 66 * s), CornerRadius(33 * s))
            drawRoundRect(Mint, Offset(240 * s, 464 * s), Size(180 * s, 30 * s), CornerRadius(15 * s))
        }
        Spacer(Modifier.width(10.dp))
        Label("Glass Home", 23.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000 - System.currentTimeMillis() % 1_000)
        }
    }
    val locale = Locale.getDefault()
    val time = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", locale)
    val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    Label(now.format(time), 42.sp, weight = FontWeight.SemiBold)
    Label(now.format(date), 16.sp, color = Text2)
}

@Composable
private fun rememberNight(): Boolean {
    var night by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val from = LocalTime.parse(Config.NIGHT_FROM)
        val to = LocalTime.parse(Config.NIGHT_TO)
        while (true) {
            val t = LocalTime.now()
            night = Config.NIGHT_ENABLED && if (from <= to) t >= from && t < to else t >= from || t < to
            delay(60_000)
        }
    }
    return night
}

/** The forecast under the clock. OK on it opens the weather location panel. */
@Composable
private fun WeatherView(state: HomeState, source: WeatherSource, glass: MutableMap<String, GlassRect>) {
    val place = source.place
    var forecast by remember(place) { mutableStateOf(place?.let { source.cached(it) }) }
    LaunchedEffect(place) {
        if (place == null) return@LaunchedEffect
        while (true) {
            val ok = runCatching { withContext(Dispatchers.IO) { source.fetch(place) } }.onSuccess { forecast = it }.isSuccess
            // At power-on the Wi-Fi is often not up yet: retry soon rather than in half an hour.
            delay(if (ok) Config.weather.refreshMs else 60_000)
        }
    }
    var focused by remember { mutableStateOf(false) }
    val select = Modifier
        .focusRequester(state.requester(HomeState.WEATHER))
        .focusProperties {
            canFocus = !state.overlayOpen
            // Down goes to the first tile, not the one nearest the weather.
            down = state.requester(state.dock.firstOrNull()?.id ?: HomeState.PLUS)
        }
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) state.focusedId = HomeState.WEATHER
        }
        .tvClick { state.weatherOpen = true }
        .focusable()
    val ring = Modifier.border(2.dp, if (focused) Color(0xE6FFFFFF) else Color.Transparent, RoundedCornerShape(14.dp))

    // One focusable for every state below, so focus stays put when the forecast arrives.
    Column(select, horizontalAlignment = Alignment.End) { Forecast(place, forecast, focused, ring, glass) }
}

@Composable
private fun Forecast(place: Place?, f: Forecast?, focused: Boolean, ring: Modifier, glass: MutableMap<String, GlassRect>) {
    Spacer(Modifier.height(12.dp))
    if (place == null || f == null) {
        // No place yet (or its first forecast still coming): a button to set one.
        Box(ring.glass("weather", glass, 14.dp).padding(horizontal = 14.dp, vertical = 9.dp)) {
            Label(if (place == null) "☁️  Set weather location" else "${place.name} · Loading weather…", 13.sp, weight = FontWeight.SemiBold)
        }
        return
    }
    val today = f.days.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(WeatherCodes.symbol(f.code, f.isDay), 18.sp)
        Spacer(Modifier.width(8.dp))
        Label("${f.temp}°", 23.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        val rest = buildString {
            append(WeatherCodes.label(f.code, f.isDay))
            if (today != null) append("  ·  H ${today.high}°  L ${today.low}°")
        }
        Label(rest, 14.sp, color = Text2)
    }
    Spacer(Modifier.height(10.dp))
    Column(
        ring.glass("weather", glass, 14.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            f.days.take(7).forEach { d ->
                Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label(d.name, 10.sp, weight = FontWeight.SemiBold)
                    Label(WeatherCodes.symbol(d.code), 13.sp)
                    Label("${d.high}°", 12.sp, weight = FontWeight.SemiBold)
                    Label("${d.low}°", 10.sp, color = Color(0x8CFFFFFF))
                    Label(d.rainPct?.takeIf { it >= 20 }?.let { "$it%" } ?: " ", 8.sp, color = Color(0xFF68C4FF), weight = FontWeight.SemiBold)
                }
            }
        }
        Label(
            if (focused) "OK to change the location" else listOf(place.name, "Weather: Open-Meteo").filter { it.isNotBlank() }.joinToString(" · "),
            7.sp, color = if (focused) Color.White else Color(0x73FFFFFF),
        )
    }
}

// ---------- dock ----------

private val SelectKeys = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_A,
)

/**
 * OK on a remote: a press runs [onClick] when the button comes up; holding it
 * runs [onLongPress] instead. A press that began elsewhere (the hold that opened
 * a menu) is ignored, so it can't also pick the first menu item.
 */
private fun Modifier.tvClick(onLongPress: (() -> Unit)? = null, onClick: () -> Unit) = composed {
    val scope = rememberCoroutineScope()
    val longPress by rememberUpdatedState(onLongPress)
    // A timer rather than key repeats: not every remote (or adb) sends repeats.
    val press = remember { arrayOfNulls<Job>(1) } // the pending long press while OK is down here
    val fired = remember { BooleanArray(1) }
    onKeyEvent { e ->
        val k = e.nativeKeyEvent
        if (k.keyCode !in SelectKeys) return@onKeyEvent false
        when (k.action) {
            AndroidKeyEvent.ACTION_DOWN -> if (k.repeatCount == 0) {
                fired[0] = false
                press[0]?.cancel()
                press[0] = scope.launch {
                    delay(700)
                    longPress?.let { fired[0] = true; it() }
                }
            } else if (k.isLongPress && press[0] != null && !fired[0]) {
                // The system's own long-press flag, when the remote sends repeats.
                press[0]?.cancel()
                longPress?.let { fired[0] = true; it() }
            }
            AndroidKeyEvent.ACTION_UP -> {
                val job = press[0]
                press[0] = null
                job?.cancel()
                if (job != null && !fired[0]) onClick()
            }
        }
        true
    }
}

@Composable
private fun Dock(state: HomeState, glass: MutableMap<String, GlassRect>, onLaunch: (TvApp) -> Unit) {
    fun requester(id: String) = state.requester(id)

    LaunchedEffect(state.focusTick) {
        val target = state.focusTarget ?: return@LaunchedEffect
        // Tiles can't take focus until a just-closed menu has left the composition,
        // and a just-added tile until it has joined it: retry for a few frames.
        repeat(10) {
            awaitFrame()
            runCatching { requester(target).requestFocus() }
            if (state.focusedId == target) return@LaunchedEffect
        }
    }
    // First start: focus the first tile once the apps are loaded. Compose has
    // usually focused + by then, as it was the only tile while they loaded.
    LaunchedEffect(state.installed.isNotEmpty()) {
        if (state.installed.isNotEmpty()) state.focus(null)
    }

    val canFocus = !state.overlayOpen
    Box(Modifier.widthIn(max = 864.dp).glass("dock", glass, 23.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.dock.forEach { app ->
                key(app.id) {
                    AppTile(
                        app,
                        Modifier.focusRequester(requester(app.id)).focusProperties { this.canFocus = canFocus },
                        onFocus = { x -> state.focusedId = app.id; state.focusedCenterX = x },
                        onLongPress = { state.menuFor = app.id },
                        onClick = { onLaunch(app) },
                    )
                }
            }
            key(HomeState.PLUS) {
                Tile(
                    Modifier.focusRequester(requester(HomeState.PLUS)).focusProperties { this.canFocus = canFocus },
                    onFocus = { x -> state.focusedId = HomeState.PLUS; state.focusedCenterX = x },
                    onClick = { state.addOpen = true },
                ) {
                    Label("+", 30.sp, Modifier.align(Alignment.Center), weight = FontWeight.Light)
                }
            }
        }
    }
}

@Composable
private fun FocusedLabel(state: HomeState) {
    val id = state.focusedId
    val name = when {
        id == null || state.overlayOpen -> ""
        id == HomeState.PLUS -> "Add"
        else -> state.dock.firstOrNull { it.id == id }?.label ?: ""
    }
    Box(Modifier.fillMaxWidth().height(28.dp)) {
        Label(
            name, 13.sp,
            Modifier.layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0))
                layout(c.maxWidth, p.height) {
                    // Centred under the focused tile; the root and this box both start 48 dp in.
                    val x = (state.focusedCenterX - 48.dp.toPx() - p.width / 2).toInt()
                    p.place(x.coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0)), 8.dp.roundToPx())
                }
            },
            weight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AppTile(
    app: TvApp,
    modifier: Modifier,
    onFocus: (Float) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Tile(modifier, onFocus, onLongPress, onClick) {
        when {
            app.banner != null -> Image(app.banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Column(
                Modifier.align(Alignment.Center).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (app.icon != null) Image(app.icon, null, Modifier.size(26.dp))
                else if (app.id == Apps.SETTINGS) Label("⚙", 20.sp)
                Label(app.label, 11.sp, weight = FontWeight.SemiBold, align = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Tile(
    modifier: Modifier,
    onFocus: (Float) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var centerX by remember { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(160), label = "tile")
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(112.dp, 63.dp)
            .onGloballyPositioned {
                centerX = it.boundsInRoot().center.x
                if (focused) onFocus(centerX) // follows the dock as it scrolls
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus(centerX)
            }
            .tvClick(onLongPress, onClick)
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Color(0x26FFFFFF))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color(0xE6FFFFFF) else GlassBorder, shape),
        content = content,
    )
}

/** The round gear button left of the clock: Up from the dock reaches it. */
@Composable
private fun GearButton(state: HomeState, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, tween(160), label = "gear")
    Box(
        modifier
            .size(44.dp)
            .focusRequester(state.requester(HomeState.GEAR))
            .focusProperties { canFocus = !state.overlayOpen }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) state.focusedId = HomeState.GEAR
            }
            .tvClick { state.settingsOpen = true }
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color(0x33FFFFFF))
            .border(1.dp, if (focused) Color.White else GlassBorder, CircleShape),
    ) {
        GearIcon(Modifier.align(Alignment.Center).size(24.dp), if (focused) Color(0xFF1C1C1E) else Color.White)
    }
}

/** A gear: eight teeth on a ring, drawn so the app needs no icon library. */
@Composable
private fun GearIcon(modifier: Modifier, color: Color) {
    // Its own layer, so clearing the centre hole only clears the gear.
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val c = center
        val r = size.minDimension / 2
        val tooth = Size(r * 0.42f, r * 0.5f)
        repeat(8) { i ->
            rotate(i * 45f, c) {
                drawRoundRect(color, Offset(c.x - tooth.width / 2, c.y - r), tooth, CornerRadius(r * 0.08f))
            }
        }
        drawCircle(color, r * 0.72f, c)
        drawCircle(Color.Transparent, r * 0.3f, c, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
    }
}

// ---------- overlays ----------

/** Quick settings: a row of icons below the clock, opened from the gear. */
@Composable
private fun QuickSettingsPanel(state: HomeState, glass: MutableMap<String, GlassRect>, onLaunch: (TvApp) -> Unit) {
    val close = { state.settingsOpen = false; state.focus(HomeState.GEAR) }
    BackHandler { close() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0x40000000)).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 84.dp)
                .glass("quick", glass, 32.dp, GlassStrong).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.quickSettings.forEachIndexed { i, item ->
                IconButton(item.id.removePrefix("@qs:"), if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    close()                 // so Back from the screen lands on the gear
                    onLaunch(item)
                }
            }
        }
    }
}

/** A round quick settings button, white when focused like the gear. */
@Composable
private fun IconButton(icon: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .tvClick(onClick = onClick)
            .focusable()
            // clip, not background(shape): its layer keeps each button cached, which
            // measured smoother on the 4K Chromecast.
            .clip(CircleShape)
            .background(if (focused) Color.White else Color(0x33FFFFFF)),
    ) {
        SettingIcon(icon, Modifier.align(Alignment.Center).size(22.dp), if (focused) Color(0xFF1C1C1E) else Color.White)
    }
}

private val WifiArcs = floatArrayOf(0.3f, 0.55f, 0.8f)
private val SliderKnobs = floatArrayOf(0.3f, 0.7f, 0.45f)

/** The quick settings icons (ids from QuickSettings), drawn like the gear. */
@Composable
private fun SettingIcon(icon: String, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val line = Stroke(w * 0.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (icon) {
            "wifi" -> {
                val c = Offset(w / 2, w * 0.78f)
                for (r in WifiArcs) {
                    drawArc(color, 225f, 90f, false, Offset(c.x - r * w * 0.6f, c.y - r * w * 0.6f), Size(r * w * 1.2f, r * w * 1.2f), style = line)
                }
                drawCircle(color, w * 0.08f, c)
            }
            "display" -> {
                drawRoundRect(color, Offset(w * 0.05f, w * 0.12f), Size(w * 0.9f, w * 0.6f), CornerRadius(w * 0.08f), style = line)
                drawLine(color, Offset(w * 0.3f, w * 0.9f), Offset(w * 0.7f, w * 0.9f), line.width, line.cap)
            }
            "apps" -> for (x in 0..1) for (y in 0..1) {
                drawRoundRect(color, Offset(w * (0.06f + x * 0.5f), w * (0.06f + y * 0.5f)), Size(w * 0.38f, w * 0.38f), CornerRadius(w * 0.08f))
            }
            // Sliders, as the gear already means "settings".
            else -> SliderKnobs.forEachIndexed { i, knob ->
                val y = w * (0.2f + i * 0.3f)
                drawLine(color, Offset(w * 0.06f, y), Offset(w * 0.94f, y), line.width, line.cap)
                drawCircle(color, w * 0.12f, Offset(w * knob, y))
            }
        }
    }
}

/** The weather location: a ZIP or postal code, looked up on Open-Meteo. Opened from the weather. */
@Composable
private fun WeatherPanel(state: HomeState, source: WeatherSource, glass: MutableMap<String, GlassRect>) {
    val keyboard = LocalSoftwareKeyboardController.current
    val close = { keyboard?.hide(); state.weatherOpen = false; state.focus(HomeState.WEATHER) }
    BackHandler { close() }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { field.requestFocus() }; keyboard?.show() }
    val place = source.place

    val lookUp = lookUp@{
        val q = text.trim()
        if (q.length < 2 || busy) return@lookUp
        keyboard?.hide()        // so an error below can be seen
        busy = true
        status = null
        scope.launch {
            val found = runCatching { withContext(Dispatchers.IO) { source.find(q) } }
            busy = false
            found.onSuccess {
                if (it == null) status = "Couldn't find \"$q\". Check it and try again."
                else { source.save(it); close() }
            }.onFailure { status = "Couldn't reach Open-Meteo. Is the TV online?" }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0x40000000)).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 84.dp).width(300.dp)
                // Nearly opaque: it sits over the forecast, which would show through.
                .glass("weatherPanel", glass, 20.dp, Color(0xF51C1C1E)).padding(10.dp),
        ) {
            Label("Weather location", 15.sp, Modifier.padding(10.dp, 6.dp, 10.dp, 2.dp), weight = FontWeight.SemiBold)
            Label(place?.let { "Now: ${it.name}" } ?: "The weather is off.", 12.sp, Modifier.padding(horizontal = 10.dp), color = Text2)
            Spacer(Modifier.height(10.dp))
            var focused by remember { mutableStateOf(false) }
            BasicTextField(
                text,
                { text = it.take(24) },
                Modifier.fillMaxWidth().padding(horizontal = 4.dp).focusRequester(field).onFocusChanged { focused = it.isFocused }
                    // A remote's Down leaves the field for Save; OK brings the keyboard back, or looks up what's typed.
                    .onPreviewKeyEvent {
                        if (it.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (it.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { runCatching { save.requestFocus() }; true }
                            // Some remotes send Enter for OK: with nothing typed yet, it opens the keyboard too.
                            in SelectKeys -> { if (text.isBlank()) keyboard?.show() else lookUp(); true }
                            else -> false
                        }
                    }
                    .clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF))
                    .border(if (focused) 2.dp else 1.dp, if (focused) Color(0xE6FFFFFF) else GlassBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { lookUp() }),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Label("ZIP or postal code", 18.sp, color = Color(0x66FFFFFF))
                        inner()
                    }
                },
            )
            status?.let { Label(it, 12.sp, Modifier.padding(10.dp, 8.dp, 10.dp, 0.dp), color = Color(0xFFFFB4A8)) }
            Spacer(Modifier.height(6.dp))
            MenuItem(if (busy) "Looking up…" else "Save", Modifier.focusRequester(save)) { lookUp() }
            if (place != null) {
                MenuItem(if (place.fahrenheit) "Show °C" else "Show °F") { source.save(place.copy(fahrenheit = !place.fahrenheit)) }
                MenuItem("Turn off weather") { source.save(null); close() }
            }
            MenuItem("Cancel") { close() }
        }
    }
}

@Composable
private fun TileMenu(state: HomeState, app: TvApp, glass: MutableMap<String, GlassRect>) {
    val close = { state.menuFor = null; state.focus(app.id) }
    BackHandler { close() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
        Column(
            Modifier.align(Alignment.Center).width(260.dp).glass("menu", glass, 20.dp, GlassStrong).padding(10.dp),
        ) {
            Label(app.label, 15.sp, Modifier.padding(10.dp, 6.dp, 10.dp, 8.dp), weight = FontWeight.SemiBold)
            MenuItem("Move left", Modifier.focusRequester(first), enabled = state.canMove(app.id, -1)) {
                state.menuFor = null; state.move(app.id, -1)
            }
            MenuItem("Move right", enabled = state.canMove(app.id, 1)) {
                state.menuFor = null; state.move(app.id, 1)
            }
            // Removing a shortcut deletes it (the phone can add it again); an app stays installed.
            MenuItem(if (Shortcuts.isShortcut(app.id)) "Remove shortcut" else "Remove from dock") {
                state.menuFor = null; state.remove(app.id)
            }
            MenuItem("Cancel") { close() }
        }
    }
}

@Composable
private fun MenuItem(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvClick { if (enabled) onClick() }
            .focusable()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        val color = when {
            focused -> Color.Black
            enabled -> Color.White
            else -> Color(0x66FFFFFF)
        }
        BasicText(text, style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun AddPanel(state: HomeState, glass: MutableMap<String, GlassRect>) {
    val close = { state.addOpen = false; state.focus(HomeState.PLUS) }
    BackHandler { close() }
    val first = remember { FocusRequester() }
    val choices = state.installed.values.filter { it.id !in state.dockIds }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
        Column(
            Modifier.align(Alignment.Center).width(720.dp).height(470.dp)
                .glass("add", glass, 26.dp, GlassStrong).padding(22.dp),
        ) {
            PhoneCard(state)
            Spacer(Modifier.height(18.dp))
            Label("Add an app", 18.sp, weight = FontWeight.SemiBold)
            Label("OK adds the app at the end. Hold OK on a dock tile to move or remove it.", 12.sp, color = Text2)
            Spacer(Modifier.height(10.dp))
            if (choices.isEmpty()) {
                Label("Every app is already in the dock.", 14.sp, color = Text2)
                Spacer(Modifier.height(12.dp))
                MenuItem("Close", Modifier.width(160.dp).focusRequester(first)) { close() }
            } else {
                LazyVerticalGrid(
                    GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                ) {
                    items(choices, key = { it.id }) { app ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AppTile(
                                app,
                                if (app == choices.first()) Modifier.focusRequester(first) else Modifier,
                                onClick = { state.addOpen = false; state.add(app.id) },
                            )
                            Label(app.label, 10.sp, Modifier.padding(top = 6.dp), color = Text2, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Where to add photos and shortcuts from a phone: the address, the password, and New password. */
@Composable
private fun PhoneCard(state: HomeState) {
    val phone = state.phone
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x14FFFFFF)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label("Photos and shortcuts from your phone", 15.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            when {
                phone == null -> Label("Starting…", 13.sp, color = Text2)
                phone.error != null -> Label("The phone website couldn't start: ${phone.error}", 13.sp, color = Text2)
                phone.url == null -> Label("Connect the TV to Wi-Fi or Ethernet to use it.", 13.sp, color = Text2)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("On a phone on the same Wi-Fi, open  ", 13.sp, color = Text2)
                        Label(phone.url.removePrefix("http://"), 15.sp, color = Mint, weight = FontWeight.SemiBold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Password  ", 13.sp, color = Text2)
                        Label(phone.password ?: "(changed on a phone)", 15.sp, color = Mint, weight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (phone?.url != null && phone.error == null) {
            Spacer(Modifier.width(12.dp))
            MenuItem("New password", Modifier.width(160.dp)) { state.newPassword() }
        }
    }
}

// ---------- text ----------

@Composable
private fun Label(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Medium,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            shadow = TextShadow,
            fontFeatureSettings = "tnum",
            textAlign = align,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
