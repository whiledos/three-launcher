package dev.whiledos.threelauncher.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Рисует иконки приложений в выбранной форме (как Pixel Launcher), в том числе «Темные иконки» Material You. */
object IconRenderer {

    const val SIZE = 192

    fun render(source: Drawable, shape: String, themed: Boolean, bg: Int, fg: Int): ImageBitmap {
        val size = SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = source.constantState?.newDrawable()?.mutate() ?: source
        val path = shapePath(shape, size.toFloat())

        c.save()
        c.clipPath(path)
        if (d is AdaptiveIconDrawable) {
            val off = (size * 0.25f).toInt() // слои — 108dp, видимая часть — 72dp
            val l = -off
            val r = size + off
            val mono: Drawable? = if (themed && Build.VERSION.SDK_INT >= 33) d.monochrome else null
            if (mono != null) {
                c.drawColor(bg)
                mono.setTint(fg)
                mono.setBounds(l, l, r, r)
                mono.draw(c)
            } else {
                d.background?.apply { setBounds(l, l, r, r); draw(c) }
                d.foreground?.apply { setBounds(l, l, r, r); draw(c) }
            }
        } else {
            c.drawColor(if (themed) bg else Color.WHITE)
            val inset = (size * 0.17f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
        }
        c.restore()
        return bmp.asImageBitmap()
    }

    private val systemMask: String? by lazy {
        try {
            val id = Resources.getSystem().getIdentifier("config_icon_mask", "string", "android")
            if (id != 0) Resources.getSystem().getString(id) else null
        } catch (e: Exception) {
            null
        }
    }

    fun shapePath(shape: String, s: Float): Path {
        val p = Path()
        val rect = RectF(0f, 0f, s, s)
        when (shape) {
            IconShapes.CIRCLE -> p.addOval(rect, Path.Direction.CW)
            IconShapes.SQUIRCLE -> {
                val n = 4.0
                val steps = 120
                for (i in 0..steps) {
                    val t = 2 * PI * i / steps
                    val cx = cos(t)
                    val sy = sin(t)
                    val x = s / 2 + s / 2 * (sign(cx) * abs(cx).pow(2 / n)).toFloat()
                    val y = s / 2 + s / 2 * (sign(sy) * abs(sy).pow(2 / n)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            IconShapes.ROUNDED -> p.addRoundRect(rect, s * 0.27f, s * 0.27f, Path.Direction.CW)
            IconShapes.SQUARE -> p.addRoundRect(rect, s * 0.09f, s * 0.09f, Path.Direction.CW)
            IconShapes.TEARDROP -> {
                val big = s * 0.5f
                val small = s * 0.12f
                p.addRoundRect(
                    rect,
                    floatArrayOf(big, big, small, small, big, big, big, big),
                    Path.Direction.CW,
                )
            }
            else -> {
                val mask = systemMask
                val parsed = try { mask?.let { PathParser.createPathFromPathData(it) } } catch (e: Exception) { null }
                if (parsed != null) {
                    val m = android.graphics.Matrix()
                    m.setScale(s / 100f, s / 100f)
                    parsed.transform(m)
                    return parsed
                }
                p.addOval(rect, Path.Direction.CW)
            }
        }
        return p
    }
}
