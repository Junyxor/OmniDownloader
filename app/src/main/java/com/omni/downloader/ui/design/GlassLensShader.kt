package com.omni.downloader.ui.design

import android.graphics.LinearGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * API 33+ optical treatment for only the moving navigation selection lens.
 *
 * A 2D rounded-rectangle signed distance field (SDF) controls:
 * - convex edge displacement of an ambient input shader;
 * - three-channel chromatic splitting close to the bevel;
 * - a narrow, direction-dependent specular rim.
 *
 * This samples a local ambient shader, not the live page below the dock.
 * That design choice keeps offscreen reads and
 * download-progress frame costs at zero, on the current Compose 1.6 toolchain.
 *
 * The RuntimeShader and input gradient are cached with drawWithCache, so selecting
 * tabs does not allocate shader programs, bitmaps, or CPU capture buffers.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun opticalGlassLensModifier(dark: Boolean): Modifier {
    val runtimeShader = remember(dark) {
        runCatching { RuntimeShader(LENS_AGSL) }.getOrNull()
    } ?: return Modifier.background(
        Brush.verticalGradient(if (dark) listOf(Color(0x806D84B6), Color(0x573C496B))
                               else listOf(Color(0xE9FFFFFF), Color(0x9CC9DDF6)))
    )

    return Modifier.drawWithCache {
        val width = size.width.coerceAtLeast(1f)
        val height = size.height.coerceAtLeast(1f)
        val gradient = LinearGradient(
            0f, 0f, width, height,
            intArrayOf(
                (if (dark) Color(0xFF4F7198) else Color(0xFFFFFFFF)).toArgb(),
                (if (dark) Color(0xFF35416D) else Color(0xFFE8F5FF)).toArgb(),
                (if (dark) Color(0xFF6A5285) else Color(0xFFD7DEF9)).toArgb()
            ),
            floatArrayOf(0f, 0.53f, 1f),
            Shader.TileMode.CLAMP
        )
        runtimeShader.setInputShader("ambient", gradient)
        runtimeShader.setFloatUniform("size", width, height)
        runtimeShader.setFloatUniform("darkness", if (dark) 1f else 0f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        onDrawBehind {
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawRect(0f, 0f, width, height, paint) }
        }
    }
}

/**
 * Local AGSL implementation with a bounded optical edge profile.
 * Three ambient.eval() taps are the only texture samples per pixel.
 * No unbounded loops or high-radius Gaussian kernels.
 */
private val LENS_AGSL = """
uniform shader ambient;
uniform float2 size;
uniform float darkness;

half4 main(float2 p) {
    float2 center = size * 0.5;
    float radius = min(size.y * 0.49, size.x * 0.5);
    float2 q = abs(p - center) - (size * 0.5 - radius);
    float sdf = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float interior = max(0.0, -sdf);
    float bevel = 1.0 - smoothstep(1.0, 13.0, interior);
    float2 n = normalize((p - center) / max(size, float2(1.0)) + float2(0.0001, 0.0001));

    // Lens-shaped displacement strongest at the perimeter, zero at the center.
    float2 bend = n * (6.0 * bevel * bevel);
    float2 uv = clamp(p - bend, float2(0.0), size - float2(1.0));

    half3 c;
    c.r = ambient.eval(clamp(uv + n * (1.5 * bevel), float2(0.0), size - float2(1.0))).r;
    c.g = ambient.eval(uv).g;
    c.b = ambient.eval(clamp(uv - n * (1.5 * bevel), float2(0.0), size - float2(1.0))).b;

    // Glass highlight from top-left; shade the opposing edge gently.
    float directional = clamp(0.55 - n.x * 0.35 - n.y * 0.65, 0.0, 1.0);
    float hairline = 1.0 - smoothstep(0.0, 2.0, interior);
    float whiteRim = hairline * (0.10 + 0.26 * directional);
    c = mix(c, half3(1.0), half(whiteRim));
    c *= half(1.0 - (1.0 - directional) * bevel * (0.05 + darkness * 0.025));

    // Keep the captured backdrop visible through the selected lens.
    return half4(c, half(0.40 + 0.22 * bevel));
}
""".trimIndent()
