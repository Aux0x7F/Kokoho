package org.tucsoncommunity.kokoho.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import org.tucsoncommunity.kokoho.BuildConfig
import org.tucsoncommunity.kokoho.alpr.KnownCamera
import org.osmdroid.config.Configuration
import java.io.File

/** Map pieces shared by the phone map and the Android Auto map. */
object MapIcons {

    /** CCTV cameras and their view: public ones slate, private ones brown-grey. */
    const val CCTV_PUBLIC_COLOR = 0xFF455A64.toInt()
    const val CCTV_PRIVATE_COLOR = 0xFF8D6E63.toInt()

    /** A small CCTV camera (body on a bracket) for the OpenStreetMap CCTV layer. */
    fun cctvIcon(dp: Float, private: Boolean): Bitmap {
        val w = (16 * dp).toInt(); val h = (14 * dp).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val color = if (private) CCTV_PRIVATE_COLOR else CCTV_PUBLIC_COLOR
        val body = RectF(1.5f * dp, 2 * dp, w - 3 * dp, 8 * dp)
        p.color = color
        c.drawRoundRect(body, 2 * dp, 2 * dp, p)
        c.drawRect(w / 2f - 1 * dp, 8 * dp, w / 2f + 1 * dp, h - 1.5f * dp, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * dp; p.color = Color.WHITE
        c.drawRoundRect(body, 2 * dp, 2 * dp, p)
        p.style = Paint.Style.FILL
        c.drawCircle(w - 4.5f * dp, 5 * dp, 1.3f * dp, p)
        return bmp
    }

    /** osmdroid: identify ourselves to the tile server (OSM tile policy), cache tiles privately. */
    fun configureOsm(context: Context) {
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
    }

    fun cameraColor(kind: KnownCamera.Kind): Int = when (kind) {
        KnownCamera.Kind.ALPR -> 0xFFB3261E.toInt()
        KnownCamera.Kind.SPEED -> 0xFFE08A00.toInt()
        KnownCamera.Kind.RED_LIGHT -> 0xFF7B1FA2.toInt()
    }

    /** A small camera glyph, distinct from the round device dots: red plate reader, amber speed, purple red-light. */
    fun cameraIcon(dp: Float, kind: KnownCamera.Kind): Bitmap {
        val w = (22 * dp).toInt(); val h = (16 * dp).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val body = RectF(1 * dp, 3 * dp, w - 1 * dp, h - 1 * dp)
        p.color = cameraColor(kind)
        c.drawRoundRect(body, 3 * dp, 3 * dp, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dp; p.color = Color.WHITE
        c.drawRoundRect(body, 3 * dp, 3 * dp, p)
        c.drawCircle(w / 2f, (h + 2 * dp) / 2f, 3.5f * dp, p)
        return bmp
    }

    const val POLICE_AIRCRAFT_COLOR = 0xFF5E35B1.toInt()
    /** Other aircraft under the map's Aircraft filter. */
    const val CLEAR_AIRCRAFT_COLOR = 0xFF1E88E5.toInt()

    /**
     * An aircraft seen from above, nose up (the marker rotates it to its track). Police /
     * government aircraft: bold purple with a white outline; other traffic: small and grey, or
     * [clear] (the map's Aircraft filter): larger, solid blue with a white outline.
     */
    fun planeIcon(dp: Float, police: Boolean, scale: Float = 1f, clear: Boolean = false): Bitmap {
        val s = ((if (police) 30 else if (clear) 26 else 18) * dp * scale).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = s.toFloat()
        val path = android.graphics.Path().apply {
            moveTo(w * 0.50f, w * 0.04f)              // nose
            lineTo(w * 0.57f, w * 0.38f)
            lineTo(w * 0.96f, w * 0.56f)              // right wing
            lineTo(w * 0.96f, w * 0.64f)
            lineTo(w * 0.57f, w * 0.56f)
            lineTo(w * 0.55f, w * 0.80f)
            lineTo(w * 0.70f, w * 0.90f)              // right tail
            lineTo(w * 0.70f, w * 0.96f)
            lineTo(w * 0.50f, w * 0.91f)
            lineTo(w * 0.30f, w * 0.96f)
            lineTo(w * 0.30f, w * 0.90f)              // left tail
            lineTo(w * 0.45f, w * 0.80f)
            lineTo(w * 0.43f, w * 0.56f)
            lineTo(w * 0.04f, w * 0.64f)
            lineTo(w * 0.04f, w * 0.56f)              // left wing
            lineTo(w * 0.43f, w * 0.38f)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (police) POLICE_AIRCRAFT_COLOR else if (clear) CLEAR_AIRCRAFT_COLOR else 0xB3707C80.toInt()
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = (if (police || clear) 1.8f else 1f) * dp * scale.coerceIn(0.7f, 1.5f)
        p.color = if (police || clear) Color.WHITE else 0xCCFFFFFF.toInt()
        c.drawPath(path, p)
        return bmp
    }

    /** A cell tower: a mast with two radio waves on a dark disc. */
    fun towerIcon(dp: Float): Bitmap {
        val s = (20 * dp).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = s / 2f
        p.color = 0xFF5E35B1.toInt(); c.drawCircle(r, r, r - 1 * dp, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dp; p.color = Color.WHITE
        c.drawCircle(r, r, r - 1 * dp, p)
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(r, r - 1 * dp, r - 3.5f * dp, r + 6 * dp, p)
        c.drawLine(r, r - 1 * dp, r + 3.5f * dp, r + 6 * dp, p)
        c.drawArc(RectF(r - 4 * dp, r - 6 * dp, r + 4 * dp, r + 2 * dp), 200f, 140f, false, p)
        c.drawArc(RectF(r - 6.5f * dp, r - 8.5f * dp, r + 6.5f * dp, r + 4.5f * dp), 205f, 130f, false, p)
        return bmp
    }

    enum class BadgeIconKind(val id: String) {
        CAR("car"),
        HELICOPTER("heli"),
        SMALL_PLANE("plane"),
        DRONE("drone"),
        PILOT("pilot"),
        RADIO("radio")
    }

    /**
     * Circular classification-colored badge icon (`CAR`, `HELICOPTER`, `SMALL_PLANE`, `DRONE`, `RADIO`)
     * with a dark drop halo, agency fill disc, crisp white rim, and crisp white inner glyph.
     */
    fun classificationBadgeIcon(dp: Float, kind: BadgeIconKind, bgColorArgb: Int): Bitmap {
        val sizePx = (30f * dp).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = sizePx.toFloat()
        val cx = w / 2f
        val cy = w / 2f
        val r = (w / 2f) - 2.0f * dp

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Subtle outer dark halo
        p.style = Paint.Style.FILL
        p.color = 0x55000000
        c.drawCircle(cx, cy + 0.6f * dp, r + 1.0f * dp, p)

        // Solid agency classification fill
        p.color = bgColorArgb
        c.drawCircle(cx, cy, r, p)

        // Crisp white ring stroke
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f * dp
        p.color = Color.WHITE
        c.drawCircle(cx, cy, r, p)

        // White inner glyph
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.7f * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        when (kind) {
            BadgeIconKind.CAR -> {
                // Cruiser roof + body silhouette + lightbar + wheels
                val roof = android.graphics.Path().apply {
                    moveTo(cx - r * 0.36f, cy - r * 0.04f)
                    lineTo(cx - r * 0.24f, cy - r * 0.34f)
                    lineTo(cx + r * 0.24f, cy - r * 0.34f)
                    lineTo(cx + r * 0.36f, cy - r * 0.04f)
                    close()
                }
                c.drawPath(roof, fill)
                // Lightbar
                c.drawRect(RectF(cx - r * 0.14f, cy - r * 0.45f, cx + r * 0.14f, cy - r * 0.36f), fill)
                // Lower body
                val body = RectF(cx - r * 0.50f, cy - r * 0.06f, cx + r * 0.50f, cy + r * 0.24f)
                c.drawRoundRect(body, 2.2f * dp, 2.2f * dp, fill)
                // Dark windshield cutouts for clarity
                val cutout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bgColorArgb
                    style = Paint.Style.FILL
                }
                c.drawCircle(cx - r * 0.28f, cy + r * 0.10f, r * 0.08f, cutout)
                c.drawCircle(cx + r * 0.28f, cy + r * 0.10f, r * 0.08f, cutout)
                // Wheels
                c.drawCircle(cx - r * 0.30f, cy + r * 0.28f, r * 0.12f, fill)
                c.drawCircle(cx + r * 0.30f, cy + r * 0.28f, r * 0.12f, fill)
            }
            BadgeIconKind.HELICOPTER -> {
                // Top-down rotorcraft: vertical fuselage + tailboom + 4-blade X rotor
                c.drawOval(RectF(cx - r * 0.17f, cy - r * 0.34f, cx + r * 0.17f, cy + r * 0.14f), fill)
                c.drawLine(cx, cy + r * 0.12f, cx, cy + r * 0.52f, stroke)
                c.drawLine(cx - r * 0.16f, cy + r * 0.48f, cx + r * 0.16f, cy + r * 0.48f, stroke)
                // 4-blade X rotor
                val blade = r * 0.48f
                c.drawLine(cx - blade, cy - r * 0.10f - blade * 0.72f, cx + blade, cy - r * 0.10f + blade * 0.72f, stroke)
                c.drawLine(cx - blade, cy - r * 0.10f + blade * 0.72f, cx + blade, cy - r * 0.10f - blade * 0.72f, stroke)
                c.drawCircle(cx, cy - r * 0.10f, r * 0.10f, fill)
            }
            BadgeIconKind.SMALL_PLANE -> {
                // Fixed-wing plane pointing north (up)
                val path = android.graphics.Path().apply {
                    moveTo(cx, cy - r * 0.56f)
                    lineTo(cx + r * 0.11f, cy - r * 0.14f)
                    lineTo(cx + r * 0.56f, cy + r * 0.08f)
                    lineTo(cx + r * 0.56f, cy + r * 0.20f)
                    lineTo(cx + r * 0.11f, cy + r * 0.09f)
                    lineTo(cx + r * 0.09f, cy + r * 0.36f)
                    lineTo(cx + r * 0.26f, cy + r * 0.48f)
                    lineTo(cx + r * 0.26f, cy + r * 0.56f)
                    lineTo(cx, cy + r * 0.48f)
                    lineTo(cx - r * 0.26f, cy + r * 0.56f)
                    lineTo(cx - r * 0.26f, cy + r * 0.48f)
                    lineTo(cx - r * 0.09f, cy + r * 0.36f)
                    lineTo(cx - r * 0.11f, cy + r * 0.09f)
                    lineTo(cx - r * 0.56f, cy + r * 0.20f)
                    lineTo(cx - r * 0.56f, cy + r * 0.08f)
                    lineTo(cx - r * 0.11f, cy - r * 0.14f)
                    close()
                }
                c.drawPath(path, fill)
            }
            BadgeIconKind.DRONE -> {
                // X-frame quadcopter with 4 corner rotor rings & center pod
                val off = r * 0.36f
                c.drawLine(cx - off, cy - off, cx + off, cy + off, stroke)
                c.drawLine(cx - off, cy + off, cx + off, cy - off, stroke)
                val rotorR = r * 0.19f
                c.drawCircle(cx - off, cy - off, rotorR, stroke)
                c.drawCircle(cx + off, cy - off, rotorR, stroke)
                c.drawCircle(cx - off, cy + off, rotorR, stroke)
                c.drawCircle(cx + off, cy + off, rotorR, stroke)
                c.drawRoundRect(RectF(cx - r * 0.16f, cy - r * 0.22f, cx + r * 0.16f, cy + r * 0.22f), 2f * dp, 2f * dp, fill)
            }
            BadgeIconKind.PILOT -> {
                // Ground operator silhouette + handheld RC controller + antenna whip
                val personX = cx - r * 0.10f
                c.drawCircle(personX, cy - r * 0.32f, r * 0.19f, fill)
                val body = android.graphics.Path().apply {
                    moveTo(personX - r * 0.38f, cy + r * 0.44f)
                    quadTo(personX - r * 0.38f, cy - r * 0.04f, personX, cy - r * 0.04f)
                    quadTo(personX + r * 0.38f, cy - r * 0.04f, personX + r * 0.38f, cy + r * 0.44f)
                    close()
                }
                c.drawPath(body, fill)
                val cutout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bgColorArgb
                    style = Paint.Style.FILL
                }
                c.drawRoundRect(
                    RectF(personX - r * 0.18f, cy + r * 0.12f, personX + r * 0.34f, cy + r * 0.38f),
                    1.4f * dp,
                    1.4f * dp,
                    cutout
                )
                c.drawRoundRect(
                    RectF(personX - r * 0.12f, cy + r * 0.17f, personX + r * 0.30f, cy + r * 0.34f),
                    1.2f * dp,
                    1.2f * dp,
                    fill
                )
                // RC transmitter antenna whip + tip
                c.drawLine(cx + r * 0.30f, cy + r * 0.14f, cx + r * 0.44f, cy - r * 0.40f, stroke)
                c.drawCircle(cx + r * 0.44f, cy - r * 0.40f, r * 0.08f, fill)
            }
            BadgeIconKind.RADIO -> {
                // Vertical mast + base dot + dual symmetrical RF wave arcs
                c.drawLine(cx, cy - r * 0.10f, cx, cy + r * 0.46f, stroke)
                c.drawCircle(cx, cy - r * 0.16f, r * 0.11f, fill)
                c.drawArc(RectF(cx - r * 0.32f, cy - r * 0.48f, cx + r * 0.32f, cy + r * 0.16f), 210f, 120f, false, stroke)
                c.drawArc(RectF(cx - r * 0.50f, cy - r * 0.66f, cx + r * 0.50f, cy + r * 0.34f), 215f, 110f, false, stroke)
            }
        }
        return bmp
    }

    /**
     * Rounded-square facility / station badge icon for `Location` directory pins
     * (DHS offices, Police/Sheriff stations, Fire departments), visually distinct from circular moving pucks.
     */
    fun facilityBadgeIcon(dp: Float, bgColorArgb: Int, isFire: Boolean = false): Bitmap {
        val sizePx = (26f * dp).toInt().coerceAtLeast(22)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = sizePx.toFloat()
        val pad = 2.2f * dp
        val rect = RectF(pad, pad, w - pad, w - pad)
        val corner = 6.0f * dp

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = 0x66000000
        c.drawRoundRect(RectF(pad, pad + 0.8f * dp, w - pad, w - pad + 0.8f * dp), corner, corner, p)

        p.color = bgColorArgb
        c.drawRoundRect(rect, corner, corner, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f * dp
        p.color = Color.WHITE
        c.drawRoundRect(rect, corner, corner, p)

        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val cx = w / 2f
        val cy = w / 2f
        val s = (w - 2f * pad) * 0.5f
        if (isFire) {
            // Crisp flame / Maltese cross silhouette
            val bar = s * 0.28f
            val len = s * 0.58f
            c.drawRoundRect(RectF(cx - bar, cy - len, cx + bar, cy + len), 1.2f * dp, 1.2f * dp, fg)
            c.drawRoundRect(RectF(cx - len, cy - bar, cx + len, cy + bar), 1.2f * dp, 1.2f * dp, fg)
        } else {
            // Crisp civic building / pediment + columns
            val roof = android.graphics.Path().apply {
                moveTo(cx, cy - s * 0.62f)
                lineTo(cx + s * 0.62f, cy - s * 0.20f)
                lineTo(cx - s * 0.62f, cy - s * 0.20f)
                close()
            }
            c.drawPath(roof, fg)
            val colW = s * 0.16f
            for (dx in listOf(-0.38f, 0f, 0.38f)) {
                val x = cx + s * dx
                c.drawRect(RectF(x - colW / 2f, cy - s * 0.14f, x + colW / 2f, cy + s * 0.40f), fg)
            }
            c.drawRect(RectF(cx - s * 0.58f, cy + s * 0.42f, cx + s * 0.58f, cy + s * 0.58f), fg)
        }
        return bmp
    }

    /** High-contrast gold/amber crosshair pin for a user-dropped map point. */
    fun droppedPinIcon(dp: Float): Bitmap {
        val sizePx = (34f * dp).toInt().coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = sizePx / 2f - 3f * dp
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = 0x77000000
        c.drawCircle(cx, cy + 0.8f * dp, r + 1.2f * dp, p)
        p.color = 0xFFD97706.toInt()
        c.drawCircle(cx, cy, r * 0.72f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.2f * dp
        p.color = 0xFFFEF3C7.toInt()
        c.drawCircle(cx, cy, r * 0.72f, p)
        p.strokeWidth = 1.8f * dp
        p.color = Color.WHITE
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, 2.4f * dp, p)
        return bmp
    }

    /** Crisp high-visibility gold-white selection halo ring drawn around the active map selection. */
    fun selectionRingIcon(dp: Float): Bitmap {
        val sizePx = (46f * dp).toInt().coerceAtLeast(38)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = sizePx / 2f - 3f * dp
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.2f * dp
            color = 0xDD0B0F17.toInt()
        }
        c.drawCircle(cx, cy, r, p)
        p.strokeWidth = 2.2f * dp
        p.color = 0xFFFBBF24.toInt()
        c.drawCircle(cx, cy, r, p)
        return bmp
    }

    /** A round device dot with a white ring. */
    fun dot(dp: Float, color: Int, sizeDp: Int, ringDp: Float = 2f) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((ringDp * dp).toInt().coerceAtLeast(1), Color.WHITE)
        val px = (sizeDp * dp).toInt()
        setSize(px, px)
    }
}
