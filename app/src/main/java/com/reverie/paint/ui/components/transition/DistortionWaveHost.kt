/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components.transition

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.RoundedCorner
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.GalleryWaveTransitionHost
import com.reverie.paint.core.Page
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.abortFailedGalleryLoadToHome
import com.reverie.paint.core.loadProject
import com.reverie.paint.model.DistortionWavePalette
import com.reverie.paint.model.DistortionWaveStyle
import com.reverie.paint.model.Project
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.model.WaveFront
import com.reverie.paint.model.WaveOffset
import com.reverie.paint.model.WaveParticle
import com.reverie.paint.model.WaveRect
import com.reverie.paint.model.WaveRgb
import com.reverie.paint.model.WaveSize
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.glassBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private enum class WavePhase {
    IDLE,
    GATHERING,
    SWEEPING,
    RETURNING,
}

private const val SLOW_LOAD_HINT_THRESHOLD_SEC = 0.60f
private const val LOAD_SAFETY_TIMEOUT_SEC = 18.0f

/**
 * 画廊 ↔ 工程扭曲波转场顶层舞台。
 *
 * 特点:
 * - 空闲时 `captureArmed == false`, 直接透传 `drawContent()`, 对 120Hz/144Hz 绘画零开销
 * - 绝不对底层 `PaintingPage` 做缩放变换, 彻底规避 `FrontBufferPreviewOverlay` 笔迹撕裂
 * - Android 13+ (API 33+) 运行完整 AGSL 双着色器 (透镜折射/渐进模糊/色散 + OKLab 色带与屏幕边缘流光)
 * - Android 6–12 (API 23–32) 自动降级为纯 Compose Canvas 卡片揭幕光波 + 边缘微光 + 微粒
 * - 波前扫过全屏 (`front.hasCleared`) 即刻解除触摸拦截, 余韵流光期间即可直接落笔
 */
@Composable
fun DistortionWaveStage(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val densityObj = LocalDensity.current
    val density = densityObj.density
    val scope = rememberCoroutineScope()
    val rootLayer = rememberGraphicsLayer()

    val themeColors = Theme.current
    val isDark = vm.isCurrentlyDark()
    val palette = remember(themeColors.accent, isDark) {
        DistortionWavePalette.fromMorandiAccent(
            accent = WaveRgb(themeColors.accent.red, themeColors.accent.green, themeColors.accent.blue),
            isDark = isDark,
        )
    }

    val shaderPipeline = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DistortionWaveShaderPipeline.createOrNull()
        } else {
            null
        }
    }

    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    var captureArmed by remember { mutableStateOf(false) }

    val controller = remember(vm, scope, shaderPipeline) {
        DistortionWaveController(
            vm = vm,
            context = context,
            scope = scope,
            shaderPipeline = shaderPipeline,
            readDensity = { densityObj.density },
            readViewportPx = { viewportPx },
            readCornerRadiusDp = { resolveScreenCornerRadiusDp(view, densityObj.density) },
            captureRootBitmap = {
                captureArmed = true
                // 等两帧以确保 drawWithContent 在本轮 draw 阶段把最新页面录进 rootLayer
                withFrameNanos { }
                withFrameNanos { }
                val bmp = runCatching { rootLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()
                captureArmed = false
                bmp
            },
        )
    }

    DisposableEffect(vm, controller) {
        vm.waveTransitionHost = controller
        onDispose {
            if (vm.waveTransitionHost === controller) {
                vm.waveTransitionHost = null
            }
            controller.cancelAndRelease()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportPx = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (captureArmed) {
                        rootLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(rootLayer)
                    } else {
                        drawContent()
                    }
                },
        ) {
            content()
        }

        val phase = controller.phase
        val front = controller.activeFront
        val style = controller.activeStyle
        val blocksTouch = when (phase) {
            WavePhase.IDLE -> false
            WavePhase.GATHERING, WavePhase.RETURNING -> true
            WavePhase.SWEEPING -> front == null || !front.hasCleared
        }

        if (phase != WavePhase.IDLE) {
            val touchModifier = if (blocksTouch) {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }
            } else {
                Modifier
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(touchModifier),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val wPx = size.width
                    val hPx = size.height
                    if (wPx < 1f || hPx < 1f) return@Canvas
                    val sizeDp = WaveSize(wPx / density, hPx / density)

                    if (controller.reducedMotionActive || front == null) {
                        // 动效关闭档: 仅覆盖画廊静态快照直到首帧就绪, 消除白屏与弹窗闪烁
                        val outBmp = controller.outgoingPyramid?.sharp
                        if (outBmp != null && !outBmp.isRecycled) {
                            drawImage(
                                image = outBmp.asImageBitmap(),
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(wPx.toInt().coerceAtLeast(1), hPx.toInt().coerceAtLeast(1)),
                            )
                        }
                        return@Canvas
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shaderPipeline != null) {
                        drawAgslWaveFrame(
                            pipeline = shaderPipeline,
                            wPx = wPx,
                            hPx = hPx,
                            density = density,
                            sizeDp = sizeDp,
                            phase = phase,
                            front = front,
                            style = style,
                            palette = palette,
                            outgoing = controller.outgoingPyramid,
                            incoming = controller.incomingPyramid,
                        )
                    } else {
                        drawFallbackWaveFrame(
                            wPx = wPx,
                            hPx = hPx,
                            density = density,
                            phase = phase,
                            front = front,
                            style = style,
                            palette = palette,
                            outgoing = controller.outgoingPyramid,
                            clipPathScratch = controller.clipPathScratch,
                        )
                    }

                    drawWaveParticles(
                        particles = controller.particles,
                        front = front,
                        density = density,
                        posScratch = controller.particlePosScratch,
                    )
                }

                // 大工程加载超时 (>0.6s) 时底部淡入轻量莫兰迪毛玻璃小胶囊, 就绪放波即刻消散
                AnimatedVisibility(
                    visible = phase == WavePhase.GATHERING && controller.showSlowLoadPill,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(140)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 32.dp),
                ) {
                    val pillShape = RoundedCornerShape(999.dp)
                    Row(
                        modifier = Modifier
                            .shadow(10.dp, pillShape, clip = false)
                            .clip(pillShape)
                            .background(Morandi.panelHi.copy(alpha = 0.92f))
                            .glassBorder(pillShape)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Morandi.accent,
                            strokeWidth = 2.2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = vm.blockingLoadingMessage.ifBlank {
                                stringResource(R.string.project_loading_progress)
                            },
                            color = Morandi.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawAgslWaveFrame(
    pipeline: DistortionWaveShaderPipeline,
    wPx: Float,
    hPx: Float,
    density: Float,
    sizeDp: WaveSize,
    phase: WavePhase,
    front: WaveFront,
    style: DistortionWaveStyle,
    palette: DistortionWavePalette,
    outgoing: WaveSnapshotPyramid?,
    incoming: WaveSnapshotPyramid?,
) {
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        when (phase) {
            WavePhase.GATHERING -> {
                if (outgoing != null) {
                    pipeline.drawLens(
                        canvas = native,
                        widthPx = wPx,
                        heightPx = hPx,
                        density = density,
                        sizeDp = sizeDp,
                        front = front,
                        style = style,
                        pyramid = outgoing,
                        sideMode = DistortionWaveShaderPipeline.SIDE_FORWARD_OUTGOING,
                    )
                }
            }

            WavePhase.SWEEPING -> {
                if (!front.hasCleared) {
                    if (incoming != null) {
                        pipeline.drawLens(
                            canvas = native,
                            widthPx = wPx,
                            heightPx = hPx,
                            density = density,
                            sizeDp = sizeDp,
                            front = front,
                            style = style,
                            pyramid = incoming,
                            sideMode = DistortionWaveShaderPipeline.SIDE_FORWARD_INCOMING,
                        )
                    }
                    if (outgoing != null) {
                        pipeline.drawLens(
                            canvas = native,
                            widthPx = wPx,
                            heightPx = hPx,
                            density = density,
                            sizeDp = sizeDp,
                            front = front,
                            style = style,
                            pyramid = outgoing,
                            sideMode = DistortionWaveShaderPipeline.SIDE_FORWARD_OUTGOING,
                        )
                    }
                }
            }

            WavePhase.RETURNING -> {
                if (outgoing != null && !front.hasCleared) {
                    pipeline.drawLens(
                        canvas = native,
                        widthPx = wPx,
                        heightPx = hPx,
                        density = density,
                        sizeDp = sizeDp,
                        front = front,
                        style = style,
                        pyramid = outgoing,
                        sideMode = DistortionWaveShaderPipeline.SIDE_RETURN_OUTGOING,
                    )
                }
            }

            WavePhase.IDLE -> Unit
        }

        pipeline.drawBand(
            canvas = native,
            widthPx = wPx,
            heightPx = hPx,
            density = density,
            sizeDp = sizeDp,
            front = front,
            style = style,
            palette = palette,
        )
    }
}

/**
 * Android 6–12 (API 23–32) 纯 Compose Canvas 降级渲染:
 * 保留卡片蓄力光晕、圆角卡片 -> 圆形扩散揭幕遮罩、莫兰迪流光波带、屏幕边缘微光与微粒。
 */
private fun DrawScope.drawFallbackWaveFrame(
    wPx: Float,
    hPx: Float,
    density: Float,
    phase: WavePhase,
    front: WaveFront,
    style: DistortionWaveStyle,
    palette: DistortionWavePalette,
    outgoing: WaveSnapshotPyramid?,
    clipPathScratch: Path,
) {
    val dstSize = IntSize(wPx.toInt().coerceAtLeast(1), hPx.toInt().coerceAtLeast(1))
    val outBmp = outgoing?.sharp?.takeIf { !it.isRecycled }
    val oxPx = front.originX * density
    val oyPx = front.originY * density
    val halfWPx = (front.boxHalfX + front.radius) * density
    val halfHPx = (front.boxHalfY + front.radius) * density
    val cornerPx = front.radius * density

    val primaryColor = palette.colors.firstOrNull()?.toComposeColor() ?: Color(0xFF879ED1)
    val aheadColor = palette.ahead.toComposeColor()
    val behindColor = palette.behind.toComposeColor()

    when (phase) {
        WavePhase.GATHERING -> {
            if (outBmp != null) {
                drawImage(image = outBmp.asImageBitmap(), dstOffset = IntOffset.Zero, dstSize = dstSize)
            }
            if (front.glow > 0.005f) {
                val cardHalfW = (front.cardHalfX + front.cardCornerRadius) * density
                val cardHalfH = (front.cardHalfY + front.cardCornerRadius) * density
                val auraExpand = (front.radius - front.cardCornerRadius + 10f).coerceAtLeast(4f) * density
                val cardR = front.cardCornerRadius * density
                // 裁剪卡片内部保持画面清晰, 柔和莫兰迪光晕贴合卡片外缘呼吸
                clipPathScratch.reset()
                clipPathScratch.addRoundRect(
                    RoundRect(
                        rect = Rect(
                            front.cardCenterX * density - cardHalfW,
                            front.cardCenterY * density - cardHalfH,
                            front.cardCenterX * density + cardHalfW,
                            front.cardCenterY * density + cardHalfH,
                        ),
                        cornerRadius = CornerRadius(cardR, cardR),
                    ),
                )
                clipPath(clipPathScratch, clipOp = ClipOp.Difference) {
                    val glowAlpha = (front.glow * 0.65f).coerceIn(0f, 0.7f)
                    drawRoundRect(
                        color = aheadColor.copy(alpha = glowAlpha),
                        topLeft = Offset(
                            front.cardCenterX * density - cardHalfW - auraExpand * 0.45f,
                            front.cardCenterY * density - cardHalfH - auraExpand * 0.45f,
                        ),
                        size = Size(
                            (cardHalfW + auraExpand * 0.45f) * 2f,
                            (cardHalfH + auraExpand * 0.45f) * 2f,
                        ),
                        cornerRadius = CornerRadius(cardR + auraExpand * 0.35f),
                        style = Stroke(width = (14f * density).coerceAtLeast(6f)),
                    )
                }
            }
        }

        WavePhase.SWEEPING -> {
            if (!front.hasCleared && outBmp != null) {
                clipPathScratch.reset()
                clipPathScratch.addRoundRect(
                    RoundRect(
                        rect = Rect(oxPx - halfWPx, oyPx - halfHPx, oxPx + halfWPx, oyPx + halfHPx),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                    ),
                )
                clipPath(clipPathScratch, clipOp = ClipOp.Difference) {
                    drawImage(image = outBmp.asImageBitmap(), dstOffset = IntOffset.Zero, dstSize = dstSize)
                }
            }
        }

        WavePhase.RETURNING -> {
            if (!front.hasCleared && outBmp != null) {
                clipPathScratch.reset()
                clipPathScratch.addRoundRect(
                    RoundRect(
                        rect = Rect(oxPx - halfWPx, oyPx - halfHPx, oxPx + halfWPx, oyPx + halfHPx),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                    ),
                )
                clipPath(clipPathScratch, clipOp = ClipOp.Intersect) {
                    drawImage(
                        image = outBmp.asImageBitmap(),
                        dstOffset = IntOffset.Zero,
                        dstSize = dstSize,
                        alpha = front.outgoingCoverMul.coerceIn(0f, 1f),
                    )
                }
            }
        }

        WavePhase.IDLE -> Unit
    }

    // 莫兰迪波前流光环 (显式按当前波前半径对齐渐变停点, 保证小卡片到全屏均呈现完整色带)
    if (front.launched && front.glow > 0.005f) {
        val bandAlpha = (front.glow * style.strength * 0.75f).coerceIn(0f, 0.85f)
        val bandWidth = (style.lead * 0.65f * density).coerceAtLeast(12f)
        val ringR = max(halfWPx, halfHPx).coerceAtLeast(1f)
        val totalR = (ringR + bandWidth * 0.65f).coerceAtLeast(2f)
        val innerStop = ((ringR - bandWidth * 0.65f) / totalR).coerceIn(0f, 0.90f)
        val behindStop = ((ringR - bandWidth * 0.22f) / totalR).coerceIn(innerStop, 0.93f)
        val crestStop = (ringR / totalR).coerceIn(behindStop, 0.96f)
        val aheadStop = ((ringR + bandWidth * 0.28f) / totalR).coerceIn(crestStop, 0.99f)
        val bandBrush = Brush.radialGradient(
            colorStops = arrayOf(
                innerStop to Color.Transparent,
                behindStop to behindColor.copy(alpha = bandAlpha * 0.55f),
                crestStop to primaryColor.copy(alpha = bandAlpha),
                aheadStop to aheadColor.copy(alpha = bandAlpha * 0.85f),
                1f to Color.Transparent,
            ),
            center = Offset(oxPx, oyPx),
            radius = totalR,
        )
        drawRoundRect(
            brush = bandBrush,
            topLeft = Offset(oxPx - halfWPx, oyPx - halfHPx),
            size = Size(halfWPx * 2f, halfHPx * 2f),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = bandWidth),
        )
    }

    // 屏幕圆角边缘柔光 + 细线余韵
    if (front.rim > 0.005f) {
        val screenR = style.cornerRadius * density
        val rimStroke = style.rimWidth * 1.4f * density
        val rimAlpha = (front.rim * 0.55f).coerceIn(0f, 0.75f)
        drawRoundRect(
            color = primaryColor.copy(alpha = rimAlpha),
            topLeft = Offset(rimStroke * 0.25f, rimStroke * 0.25f),
            size = Size((wPx - rimStroke * 0.5f).coerceAtLeast(1f), (hPx - rimStroke * 0.5f).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(screenR, screenR),
            style = Stroke(width = rimStroke),
        )
        val hairAlpha = (front.rim * style.hairlineOpacity * 0.75f).coerceIn(0f, 0.9f)
        val hairW = (style.hairlineWidth * density).coerceAtLeast(1f)
        drawRoundRect(
            color = Color.White.copy(alpha = hairAlpha),
            topLeft = Offset(hairW * 0.5f, hairW * 0.5f),
            size = Size((wPx - hairW).coerceAtLeast(1f), (hPx - hairW).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(screenR, screenR),
            style = Stroke(width = hairW),
        )
    }
}

private fun DrawScope.drawWaveParticles(
    particles: List<WaveParticle>,
    front: WaveFront,
    density: Float,
    posScratch: FloatArray,
) {
    if (particles.isEmpty()) return
    for (i in particles.indices) {
        val p = particles[i]
        val alpha = p.opacity(front)
        if (alpha <= 0.01f) continue
        p.positionInto(front, posScratch)
        val xPx = posScratch[0] * density
        val yPx = posScratch[1] * density
        if (xPx < -16f || yPx < -16f || xPx > size.width + 16f || yPx > size.height + 16f) continue
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = (p.sizeDp * 0.5f * density).coerceAtLeast(1f),
            center = Offset(xPx, yPx),
        )
    }
}

private fun WaveRgb.toComposeColor(): Color = Color(red = r, green = g, blue = b, alpha = 1f)

private fun resolveScreenCornerRadiusDp(view: android.view.View, density: Float): Float {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val insets = view.rootWindowInsets
        val tl = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        val tr = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
        val bestPx = max(tl, tr)
        if (bestPx > 0) {
            return (bestPx / density.coerceAtLeast(1f)).coerceIn(14f, 56f)
        }
    }
    return 32f
}

private fun isSystemAnimatorDisabled(context: Context): Boolean =
    try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) <= 0.001f
    } catch (_: Throwable) {
        false
    }

/**
 * 扭曲波转场状态机控制器 (由 [DistortionWaveStage] 持有)。
 */
private class DistortionWaveController(
    private val vm: PaintViewModel,
    private val context: Context,
    private val scope: CoroutineScope,
    private val shaderPipeline: DistortionWaveShaderPipeline?,
    private val readDensity: () -> Float,
    private val readViewportPx: () -> IntSize,
    private val readCornerRadiusDp: () -> Float,
    private val captureRootBitmap: suspend () -> Bitmap?,
) : GalleryWaveTransitionHost {

    var phase by mutableStateOf(WavePhase.IDLE)
        private set
    var activeFront by mutableStateOf<WaveFront?>(null)
        private set
    var activeStyle by mutableStateOf(DistortionWaveStyle.StudioNormal)
        private set
    var showSlowLoadPill by mutableStateOf(false)
        private set
    var reducedMotionActive by mutableStateOf(false)
        private set

    var outgoingPyramid: WaveSnapshotPyramid? = null
        private set
    var incomingPyramid: WaveSnapshotPyramid? = null
        private set
    var particles: List<WaveParticle> = emptyList()
        private set

    val particlePosScratch = FloatArray(2)
    val clipPathScratch = Path()

    private var activeJob: Job? = null

    private fun isWaveEffectEnabled(): Boolean =
        vm.canvasOpenWaveEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override fun tryOpenProjectFromGallery(
        project: Project,
        cardBoundsPx: WaveRect?,
        focusPx: WaveOffset?,
        delayCaptureFrames: Int,
    ): Boolean {
        if (!isWaveEffectEnabled()) return false
        if (phase != WavePhase.IDLE || activeJob?.isActive == true) {
            // 防止双击卡片重复触发加载
            return true
        }
        val viewport = readViewportPx()
        if (viewport.width < 16 || viewport.height < 16) {
            return false
        }

        val speed = Motion.currentSpeed
        val reduced = speed == UiAnimationSpeed.OFF || isSystemAnimatorDisabled(context)
        val density = readDensity().coerceAtLeast(1f)
        val sizeDp = WaveSize(viewport.width / density, viewport.height / density)
        val sourceDp = cardBoundsPx?.scaleBy(1f / density)?.takeIf { it.isValid }
        val focusDp = focusPx?.let { WaveOffset(it.x / density, it.y / density) }
        val style = DistortionWaveStyle.forSpeed(speed, readCornerRadiusDp())

        vm.lastOpenedProjectPath = project.filePath
        vm.lastOpenedCardBoundsPx = cardBoundsPx

        activeJob = scope.launch {
            var blurJob: Job? = null
            try {
                if (delayCaptureFrames > 0) {
                    repeat(delayCaptureFrames) { withFrameNanos { } }
                }
                val galleryBmp = captureRootBitmap()
                if (galleryBmp == null || galleryBmp.isRecycled) {
                    vm.loadProject(project)
                    return@launch
                }

                releasePyramids()
                val outPyr = WaveSnapshotPyramid(sharp = galleryBmp)
                outgoingPyramid = outPyr

                val needAgslBlur = !reduced &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    shaderPipeline != null
                if (needAgslBlur) {
                    blurJob = scope.launch(Dispatchers.Default) {
                        SnapshotBlurHelper.populateBlurs(outPyr, density, style.nearBlur, style.blur)
                    }
                }

                activeStyle = style
                reducedMotionActive = reduced
                particles = if (reduced) emptyList() else List(style.particleCount) { WaveParticle(it, style) }
                showSlowLoadPill = false
                vm.isWaveTransitionManaged = true
                vm.suppressPaintingLoadingOverlay = true
                vm.paintingChromeRevealed = false
                phase = WavePhase.GATHERING
                activeFront = if (reduced) {
                    null
                } else {
                    WaveFront.gathering(0f, sourceDp, sizeDp, style, focusDp)
                }

                val startOutcomeVersion = vm.loadProjectOutcomeVersion
                vm.loadProject(project)

                // Phase 1: 蓄力等待工程加载完成 + 首帧瓦片就绪
                val gatherStartNs = withFrameNanos { it }
                var elapsedSec = 0f
                var loadedReady = false

                while (true) {
                    val nowNs = withFrameNanos { it }
                    elapsedSec = ((nowNs - gatherStartNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
                    if (!reduced) {
                        activeFront = WaveFront.gathering(elapsedSec, sourceDp, sizeDp, style, focusDp)
                    }
                    if (!showSlowLoadPill && elapsedSec >= SLOW_LOAD_HINT_THRESHOLD_SEC) {
                        showSlowLoadPill = true
                    }

                    // 工程加载失败: 平滑退回画廊并弹提示, 不暴露空白画布
                    val hasOutcome = vm.loadProjectOutcomeVersion != startOutcomeVersion
                    if (hasOutcome && !vm.lastLoadProjectSucceeded) {
                        vm.abortFailedGalleryLoadToHome()
                        Toast.makeText(
                            context,
                            R.string.toast_project_import_unsupported,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }

                    // 外部切走了页面: 安全退出转场
                    if (vm.currentPage != Page.PAINTING) {
                        return@launch
                    }

                    val frameReady = hasOutcome &&
                        vm.lastLoadProjectSucceeded &&
                        !vm.isBlockingLoading &&
                        !vm.awaitingFirstFrameAfterLoad
                    val minAntDone = reduced || elapsedSec >= style.anticipation
                    if (frameReady && minAntDone) {
                        loadedReady = true
                        break
                    }
                    if (elapsedSec >= LOAD_SAFETY_TIMEOUT_SEC) {
                        break
                    }
                }

                if (!loadedReady) {
                    return@launch
                }
                if (reduced) {
                    withFrameNanos { }
                    return@launch
                }

                // 蓄力与首帧皆已就绪: 若走 AGSL, 截取单帧成品画布并极速生成模糊金字塔 (期间保持蓄力呼吸不断帧)
                showSlowLoadPill = false
                if (needAgslBlur) {
                    val paintingBmp = captureRootBitmap()
                    if (paintingBmp != null && !paintingBmp.isRecycled) {
                        val inPyr = WaveSnapshotPyramid(sharp = paintingBmp)
                        incomingPyramid = inPyr
                        val inBlurJob = scope.launch(Dispatchers.Default) {
                            SnapshotBlurHelper.populateBlurs(inPyr, density, style.nearBlur, style.blur)
                        }
                        while (inBlurJob.isActive || blurJob?.isActive == true) {
                            val nowNs = withFrameNanos { it }
                            elapsedSec = ((nowNs - gatherStartNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
                            activeFront = WaveFront.gathering(elapsedSec, sourceDp, sizeDp, style, focusDp)
                        }
                    }
                } else {
                    // API 23-32 Canvas 降级直接露出底层实时 PaintingPage, 等 1 帧确保首帧瓦片已上屏
                    withFrameNanos { }
                }

                // Phase 2: C1 连续放波扫屏 (先呈现纯净画布, 波前扫过全屏且沉浸式系统栏稳定后立刻释放各面板丝滑入场)
                val launchTimeSec = elapsedSec
                val sweepStartNs = withFrameNanos { it }
                phase = WavePhase.SWEEPING
                val totalPostLaunch = style.postLaunchDuration

                while (true) {
                    val nowNs = withFrameNanos { it }
                    val sinceLaunch = ((nowNs - sweepStartNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
                    if (vm.currentPage != Page.PAINTING) {
                        break
                    }
                    val front = WaveFront.sweeping(
                        sinceLaunchSec = sinceLaunch,
                        launchTimeSec = launchTimeSec,
                        source = sourceDp,
                        size = sizeDp,
                        style = style,
                        focus = focusDp,
                    )
                    activeFront = front
                    if (front.hasCleared && !vm.paintingChromeRevealed) {
                        vm.paintingChromeRevealed = true
                    }
                    if (sinceLaunch >= totalPostLaunch) {
                        break
                    }
                }
            } finally {
                blurJob?.cancel()
                resetToIdle()
            }
        }
        return true
    }

    override fun tryReturnToGallery(performGoHome: () -> Unit): Boolean {
        if (!isWaveEffectEnabled()) return false
        if (phase != WavePhase.IDLE || activeJob?.isActive == true) {
            return false
        }
        val speed = Motion.currentSpeed
        if (speed == UiAnimationSpeed.OFF || isSystemAnimatorDisabled(context)) {
            return false
        }
        val viewport = readViewportPx()
        if (viewport.width < 16 || viewport.height < 16) {
            return false
        }

        val density = readDensity().coerceAtLeast(1f)
        val sizeDp = WaveSize(viewport.width / density, viewport.height / density)
        val style = DistortionWaveStyle.forSpeed(speed, readCornerRadiusDp())

        activeJob = scope.launch {
            var blurJob: Job? = null
            try {
                // 若刚结束保存流程, 先撤掉绘画页加载遮罩避免截图残留转圈弹层
                vm.suppressPaintingLoadingOverlay = true
                val paintingBmp = captureRootBitmap()
                if (paintingBmp == null || paintingBmp.isRecycled) {
                    vm.suppressPaintingLoadingOverlay = false
                    performGoHome()
                    return@launch
                }

                releasePyramids()
                val outPyr = WaveSnapshotPyramid(sharp = paintingBmp)
                outgoingPyramid = outPyr

                val needAgslBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shaderPipeline != null
                if (needAgslBlur) {
                    blurJob = scope.launch(Dispatchers.Default) {
                        SnapshotBlurHelper.populateBlurs(outPyr, density, style.nearBlur, style.blur)
                    }
                }

                activeStyle = style
                reducedMotionActive = false
                particles = List(min(style.particleCount, 40)) { WaveParticle(it, style) }
                showSlowLoadPill = false

                // 锁定当前画布截图覆盖全屏, 底层立即切回实时画廊 (跳过画廊卡片入场级联防止闪跳)
                val cachedBoundsInViewport = vm.lastOpenedCardBoundsPx?.takeIf {
                    it.isValid &&
                        it.left >= -24f &&
                        it.top >= -24f &&
                        it.right <= viewport.width + 24f &&
                        it.bottom <= viewport.height + 24f
                }
                vm.lastOpenedCardBoundsPx = null
                vm.isWaveTransitionManaged = true
                vm.suppressGalleryEntranceOnce = true
                phase = WavePhase.RETURNING
                val initialSourceDp = cachedBoundsInViewport?.scaleBy(1f / density)?.takeIf { it.isValid }
                activeFront = WaveFront.returning(0f, initialSourceDp, sizeDp, style)

                performGoHome()

                // 等 1 帧让画廊布局完成并刷新最新卡片位置 (同时模糊任务几乎已就绪)
                withFrameNanos { }
                if (blurJob != null && blurJob.isActive) {
                    withContext(Dispatchers.Default) { blurJob.join() }
                }

                val startNs = withFrameNanos { it }
                val durationSec = style.returnDuration.coerceAtLeast(0.12f)
                while (true) {
                    val nowNs = withFrameNanos { it }
                    val elapsed = ((nowNs - startNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
                    if (vm.currentPage != Page.HOME) {
                        break
                    }
                    val targetBounds = vm.lastOpenedCardBoundsPx ?: cachedBoundsInViewport
                    val liveSourceDp = targetBounds?.scaleBy(1f / density)?.takeIf { it.isValid }
                    val p = (elapsed / durationSec).coerceIn(0f, 1f)
                    activeFront = WaveFront.returning(p, liveSourceDp, sizeDp, style)
                    if (p >= 1f) {
                        break
                    }
                }
            } finally {
                blurJob?.cancel()
                resetToIdle()
            }
        }
        return true
    }

    override fun tryCreateCanvas(
        focusPx: WaveOffset?,
        performCreate: () -> Unit,
    ): Boolean {
        if (!isWaveEffectEnabled()) return false
        if (phase != WavePhase.IDLE || activeJob?.isActive == true) {
            return false
        }
        val viewport = readViewportPx()
        if (viewport.width < 16 || viewport.height < 16) {
            return false
        }

        val speed = Motion.currentSpeed
        val reduced = speed == UiAnimationSpeed.OFF || isSystemAnimatorDisabled(context)
        val density = readDensity().coerceAtLeast(1f)
        val sizeDp = WaveSize(viewport.width / density, viewport.height / density)
        val focusDp = focusPx?.let { WaveOffset(it.x / density, it.y / density) }
            ?: WaveOffset(sizeDp.width / 2f, sizeDp.height * 0.85f)
        val sourceDp = WaveRect(
            left = focusDp.x - 60f,
            top = focusDp.y - 24f,
            right = focusDp.x + 60f,
            bottom = focusDp.y + 24f,
        )
        val style = DistortionWaveStyle.forSpeed(speed, readCornerRadiusDp())

        activeJob = scope.launch {
            var blurJob: Job? = null
            try {
                val createPageBmp = captureRootBitmap()
                if (createPageBmp == null || createPageBmp.isRecycled) {
                    performCreate()
                    return@launch
                }

                releasePyramids()
                val outPyr = WaveSnapshotPyramid(sharp = createPageBmp)
                outgoingPyramid = outPyr

                val needAgslBlur = !reduced &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    shaderPipeline != null
                if (needAgslBlur) {
                    blurJob = scope.launch(Dispatchers.Default) {
                        SnapshotBlurHelper.populateBlurs(outPyr, density, style.nearBlur, style.blur)
                    }
                }

                activeStyle = style
                reducedMotionActive = reduced
                particles = if (reduced) emptyList() else List(style.particleCount) { WaveParticle(it, style) }
                showSlowLoadPill = false
                vm.isWaveTransitionManaged = true
                vm.suppressPaintingLoadingOverlay = true
                vm.paintingChromeRevealed = false
                phase = WavePhase.GATHERING
                activeFront = if (reduced) null else WaveFront.gathering(0f, sourceDp, sizeDp, style, focusDp)

                performCreate()

                // Phase 1: 蓄力等待新画布创建完成 + 首帧就绪
                val gatherStartNs = withFrameNanos { it }
                var elapsedSec = 0f
                var loadedReady = false

                while (true) {
                    val nowNs = withFrameNanos { it }
                    elapsedSec = ((nowNs - gatherStartNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
                    if (!reduced) {
                        activeFront = WaveFront.gathering(elapsedSec, sourceDp, sizeDp, style, focusDp)
                    }
                    if (!showSlowLoadPill && elapsedSec >= SLOW_LOAD_HINT_THRESHOLD_SEC) {
                        showSlowLoadPill = true
                    }

                    if (!vm.isBlockingLoading && !vm.awaitingFirstFrameAfterLoad) {
                        loadedReady = true
                        break
                    }
                    if (vm.currentPage != Page.PAINTING && !vm.isBlockingLoading) {
                        break
                    }
                }

                if (!loadedReady) {
                    return@launch
                }

                blurJob?.join()
                phase = WavePhase.SWEEPING
                val launchTimeSec = elapsedSec
                val sweepStartNs = withFrameNanos { it }
                val totalPostLaunch = style.postLaunchDuration

                while (true) {
                    val nowNs = withFrameNanos { it }
                    val sinceLaunch = ((nowNs - sweepStartNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
                    if (vm.currentPage != Page.PAINTING) {
                        break
                    }
                    val front = WaveFront.sweeping(
                        sinceLaunchSec = sinceLaunch,
                        launchTimeSec = launchTimeSec,
                        source = sourceDp,
                        size = sizeDp,
                        style = style,
                        focus = focusDp,
                    )
                    activeFront = front
                    if (front.hasCleared && !vm.paintingChromeRevealed) {
                        vm.paintingChromeRevealed = true
                    }
                    if (sinceLaunch >= totalPostLaunch) {
                        break
                    }
                }
            } finally {
                blurJob?.cancel()
                resetToIdle()
            }
        }
        return true
    }

    fun cancelAndRelease() {
        activeJob?.cancel()
        activeJob = null
        resetToIdle()
    }

    private fun resetToIdle() {
        phase = WavePhase.IDLE
        activeFront = null
        showSlowLoadPill = false
        reducedMotionActive = false
        particles = emptyList()
        vm.suppressPaintingLoadingOverlay = false
        vm.paintingChromeRevealed = true
        vm.isWaveTransitionManaged = false
        vm.suppressGalleryEntranceOnce = false
        releasePyramids()
    }

    private fun releasePyramids() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shaderPipeline?.clearBoundShaders()
        }
        outgoingPyramid?.release()
        outgoingPyramid = null
        incomingPyramid?.release()
        incomingPyramid = null
    }
}
