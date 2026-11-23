package app.pairline.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.theme.Brand
import app.pairline.android.ui.theme.PairlineThemeExt
import kotlin.math.PI
import kotlin.math.sin

/** How the two-phones illustration looks. */
enum class LinkVisual { CONNECTED, SEARCHING, NOT_PAIRED, PROBLEM }

/**
 * Two phones joined by a line: the Android bridge and the iPhone. Animated: data flows along the line when
 * connected, a radar pulse searches while waiting. Mirrors in right-to-left layouts.
 */
@Composable
fun LinkIllustration(visual: LinkVisual, modifier: Modifier = Modifier, showTraffic: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val status = PairlineThemeExt.status
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val t = rememberInfiniteTransition(label = "link")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "flow")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing)), label = "pulse")
    val sms = rememberVectorPainter(PIcons.Sms)
    val call = rememberVectorPainter(PIcons.Phone)

    val lineColor = when (visual) {
        LinkVisual.CONNECTED -> status.good
        LinkVisual.SEARCHING -> cs.primary
        LinkVisual.NOT_PAIRED -> cs.outline
        LinkVisual.PROBLEM -> status.bad
    }
    val body = cs.onSurface.copy(alpha = 0.85f)
    val screenTop = cs.primaryContainer
    val screenBottom = cs.surfaceContainerHighest

    Canvas(modifier.fillMaxWidth().aspectRatio(2.2f)) {
        val w = size.width
        val h = size.height
        val phoneH = h * 0.82f
        val phoneW = phoneH * 0.52f
        val top = (h - phoneH) / 2
        val androidLeft = w * 0.12f
        val iphoneLeft = w * 0.88f - phoneW
        val (aX, iX) = if (rtl) iphoneLeft to androidLeft else androidLeft to iphoneLeft
        val gradient = Brush.verticalGradient(listOf(screenTop, screenBottom), top, top + phoneH)

        // Search pulse behind the bridge phone.
        if (visual == LinkVisual.SEARCHING) {
            val c = Offset(aX + phoneW / 2, h / 2)
            for (k in 0..1) {
                val p = (pulse + k * 0.5f) % 1f
                drawCircle(lineColor.copy(alpha = (1f - p) * 0.35f), radius = phoneH * (0.35f + p * 0.55f), center = c, style = Stroke(3.dp.toPx()))
            }
        }

        drawPhone(aX, top, phoneW, phoneH, body, gradient, iphone = false)
        drawPhone(iX, top, phoneW, phoneH, body, gradient, iphone = true)

        // The line between them.
        val y = h / 2
        val start = if (rtl) aX else aX + phoneW
        val end = if (rtl) iX + phoneW else iX
        val from = Offset(start + 6.dp.toPx() * (if (rtl) -1 else 1), y)
        val to = Offset(end - 6.dp.toPx() * (if (rtl) -1 else 1), y)
        val stroke = 4.dp.toPx()
        when (visual) {
            LinkVisual.CONNECTED -> {
                drawLine(lineColor.copy(alpha = 0.35f), from, to, stroke, StrokeCap.Round)
                for (k in 0 until 3) {
                    val p = (flow + k / 3f) % 1f
                    val forward = k % 2 == 0
                    val pos = lerp(from, to, if (forward) p else 1f - p)
                    drawCircle(lineColor, radius = stroke * 1.1f, center = pos, alpha = sin(p * PI).toFloat().coerceIn(0.2f, 1f))
                }
                if (showTraffic) {
                    val p = flow
                    val iconSize = 22.dp.toPx()
                    val pos = lerp(from, to, p)
                    val lift = sin(p * PI).toFloat() * h * 0.22f
                    translate(pos.x - iconSize / 2, pos.y - iconSize / 2 - lift) {
                        with(if (p < 0.5f) sms else call) {
                            draw(Size(iconSize, iconSize), alpha = sin(p * PI).toFloat(), colorFilter = ColorFilter.tint(cs.primary))
                        }
                    }
                }
            }
            LinkVisual.SEARCHING -> {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()), -flow * 36.dp.toPx())
                drawLine(lineColor.copy(alpha = 0.7f), from, to, stroke, StrokeCap.Round, pathEffect = dash)
            }
            LinkVisual.NOT_PAIRED -> {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 10.dp.toPx()), 0f)
                drawLine(lineColor.copy(alpha = 0.6f), from, to, stroke, StrokeCap.Round, pathEffect = dash)
            }
            LinkVisual.PROBLEM -> {
                val mid = lerp(from, to, 0.5f)
                val gap = 14.dp.toPx()
                drawLine(lineColor.copy(alpha = 0.7f), from, Offset(mid.x - gap, y), stroke, StrokeCap.Round)
                drawLine(lineColor.copy(alpha = 0.7f), Offset(mid.x + gap, y), to, stroke, StrokeCap.Round)
                val x = 7.dp.toPx()
                drawLine(lineColor, Offset(mid.x - x, y - x), Offset(mid.x + x, y + x), stroke, StrokeCap.Round)
                drawLine(lineColor, Offset(mid.x - x, y + x), Offset(mid.x + x, y - x), stroke, StrokeCap.Round)
            }
        }
    }
}

private fun lerp(a: Offset, b: Offset, f: Float) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)

private fun DrawScope.drawPhone(x: Float, y: Float, w: Float, h: Float, body: Color, screen: Brush, iphone: Boolean) {
    val radius = if (iphone) w * 0.2f else w * 0.13f
    val border = 3.dp.toPx()
    drawRoundRect(screen, Offset(x, y), Size(w, h), CornerRadius(radius))
    drawRoundRect(body, Offset(x, y), Size(w, h), CornerRadius(radius), style = Stroke(border))
    if (iphone) {
        // Dynamic-island pill.
        val pw = w * 0.32f
        drawRoundRect(body, Offset(x + (w - pw) / 2, y + h * 0.05f), Size(pw, h * 0.035f), CornerRadius(h))
    } else {
        // Punch-hole camera and a SIM card on the screen.
        drawCircle(body, radius = w * 0.045f, center = Offset(x + w / 2, y + h * 0.065f))
        val sw = w * 0.36f
        val sh = sw * 1.3f
        val sx = x + (w - sw) / 2
        val sy = y + (h - sh) / 2
        val sim = Path().apply {
            moveTo(sx + sw * 0.35f, sy)
            lineTo(sx + sw, sy)
            lineTo(sx + sw, sy + sh)
            lineTo(sx, sy + sh)
            lineTo(sx, sy + sw * 0.35f)
            close()
        }
        drawPath(sim, body.copy(alpha = 0.55f), style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        drawRoundRect(body.copy(alpha = 0.45f), Offset(sx + sw * 0.25f, sy + sh * 0.45f), Size(sw * 0.5f, sh * 0.3f), CornerRadius(3.dp.toPx()))
    }
}

/** A round icon with soft rings expanding behind it, for wizard steps. */
@Composable
fun PulseIcon(icon: ImageVector, tone: Tone = Tone.ACCENT, size: Dp = 168.dp) {
    val c = toneColors(tone)
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    val bob by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val painter = rememberVectorPainter(icon)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            for (k in 0..2) {
                val q = (p + k / 3f) % 1f
                drawCircle(c.content.copy(alpha = (1f - q) * 0.22f), radius = r * (0.45f + q * 0.55f))
            }
            drawCircle(c.container, radius = r * 0.46f)
            val iconSize = r * 0.5f
            translate(center.x - iconSize / 2, center.y - iconSize / 2 + bob * 3.dp.toPx()) {
                with(painter) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(c.content)) }
            }
        }
    }
}

/** A battery that fills and keeps a bolt on it: "let Pairline run in the background". */
@Composable
fun BatteryIllustration(size: Dp = 168.dp) {
    val cs = MaterialTheme.colorScheme
    val good = PairlineThemeExt.status.good
    val t = rememberInfiniteTransition(label = "battery")
    val level by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lvl")
    val bolt = rememberVectorPainter(PIcons.Bolt)
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(cs.primaryContainer.copy(alpha = 0.6f), radius = r)
        val bw = r * 0.62f
        val bh = r * 1.1f
        val x = center.x - bw / 2
        val y = center.y - bh / 2 + r * 0.05f
        val stroke = 4.dp.toPx()
        drawRoundRect(cs.onSurface.copy(alpha = 0.8f), Offset(x + bw * 0.32f, y - r * 0.1f), Size(bw * 0.36f, r * 0.12f), CornerRadius(3.dp.toPx()))
        drawRoundRect(cs.onSurface.copy(alpha = 0.8f), Offset(x, y), Size(bw, bh), CornerRadius(10.dp.toPx()), style = Stroke(stroke))
        val inset = stroke * 1.6f
        val innerH = bh - inset * 2
        val fillH = innerH * level
        val fillColor = if (level > 0.35f) good else Brand.Amber
        drawRoundRect(fillColor, Offset(x + inset, y + inset + innerH - fillH), Size(bw - inset * 2, fillH), CornerRadius(6.dp.toPx()))
        val s = bw * 0.62f
        translate(center.x - s / 2, y + bh / 2 - s / 2) {
            with(bolt) { draw(Size(s, s), colorFilter = ColorFilter.tint(Color.White)) }
        }
    }
}

/** A check mark that draws itself, with a burst of dots: "all set". */
@Composable
fun SuccessIllustration(size: Dp = 168.dp) {
    val good = PairlineThemeExt.status.good
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "done")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "p")
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        val draw = (p / 0.45f).coerceIn(0f, 1f)
        val burst = ((p - 0.35f) / 0.5f).coerceIn(0f, 1f)
        // Confetti.
        val colors = listOf(cs.primary, good, Color(0xFFF59F00), cs.tertiary)
        for (i in 0 until 12) {
            val a = i * (2 * PI / 12) + 0.3
            val d = r * (0.55f + burst * 0.4f)
            val c = Offset(center.x + (kotlin.math.cos(a) * d).toFloat(), center.y + (sin(a) * d).toFloat())
            drawCircle(colors[i % colors.size], radius = 4.dp.toPx() * (1f - burst * 0.6f), center = c, alpha = (1f - burst).coerceIn(0f, 1f))
        }
        drawCircle(good.copy(alpha = 0.18f), radius = r * 0.55f)
        drawCircle(good, radius = r * 0.42f)
        val path = Path().apply {
            moveTo(center.x - r * 0.2f, center.y + r * 0.01f)
            lineTo(center.x - r * 0.05f, center.y + r * 0.16f)
            lineTo(center.x + r * 0.22f, center.y - r * 0.14f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * draw, partial, true)
        drawPath(partial, Color.White, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** An iPhone pointing its camera at a QR code, with a scan line: how pairing works. */
@Composable
fun ScanIllustration(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "scan")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    val qr = rememberVectorPainter(PIcons.Qr)
    Canvas(modifier.size(120.dp)) {
        val w = size.width
        val h = size.height
        val pw = w * 0.56f
        val ph = h * 0.92f
        val x = (w - pw) / 2
        val y = (h - ph) / 2
        drawRoundRect(cs.surfaceContainerHighest, Offset(x, y), Size(pw, ph), CornerRadius(pw * 0.2f))
        drawRoundRect(cs.onSurface.copy(alpha = 0.8f), Offset(x, y), Size(pw, ph), CornerRadius(pw * 0.2f), style = Stroke(3.dp.toPx()))
        val q = pw * 0.6f
        val qx = x + (pw - q) / 2
        val qy = y + (ph - q) / 2
        translate(qx, qy) { with(qr) { draw(Size(q, q), colorFilter = ColorFilter.tint(cs.onSurface)) } }
        val ly = qy + q * p
        drawLine(cs.primary, Offset(qx - 6.dp.toPx(), ly), Offset(qx + q + 6.dp.toPx(), ly), 3.dp.toPx(), StrokeCap.Round)
        drawRect(cs.primary.copy(alpha = 0.12f), Offset(qx, qy), Size(q, q * p))
    }
}
