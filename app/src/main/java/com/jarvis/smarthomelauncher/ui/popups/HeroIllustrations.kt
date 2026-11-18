package com.jarvis.smarthomelauncher.ui.popups

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// ---------------------------------------------------------------------------
// CUSTOM JAGUAR & MERCEDES-BENZ CAR ILLUSTRATION
// - Used on both the Vehicle Hero Card and the Executive Garage Card
// ---------------------------------------------------------------------------
@Composable
fun ExecutiveVehicleCarIllustration(
    isJaguar: Boolean,
    isLocked: Boolean,
    isClimateActive: Boolean,
    isCharging: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cy = h * 0.54f

        // 1. Soft Ambient Ground Shadow & Underglow
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = if (isCharging || isClimateActive) 0.36f else 0.18f),
                    Color.Black.copy(alpha = 0.55f),
                    Color.Transparent
                ),
                center = Offset(w * 0.50f, cy + h * 0.22f),
                radius = w * 0.46f
            ),
            topLeft = Offset(w * 0.08f, cy + h * 0.08f),
            size = Size(w * 0.84f, h * 0.28f)
        )

        // 2. Sculpted Coupe-SUV / Saloon Body Silhouette
        val bodyPath = Path().apply {
            moveTo(w * 0.10f, cy + h * 0.14f)
            lineTo(w * 0.08f, cy + h * 0.02f)
            quadraticTo(w * 0.10f, cy - h * 0.10f, w * 0.24f, cy - h * 0.12f)
            // Windscreen & Sweeping Roofline
            lineTo(w * 0.37f, cy - h * 0.30f)
            quadraticTo(w * 0.52f, cy - h * 0.34f, w * 0.68f, cy - h * 0.28f)
            lineTo(w * 0.82f, cy - h * 0.12f)
            // Rear Haunch & Tail
            quadraticTo(w * 0.92f, cy - h * 0.08f, w * 0.92f, cy + h * 0.04f)
            lineTo(w * 0.90f, cy + h * 0.14f)
            close()
        }

        val bodyPrimary = if (isJaguar) Color(0xFF1F332E) else Color(0xFF1E2938)
        val bodyHighlight = if (isJaguar) Color(0xFF32524A) else Color(0xFF334760)

        drawPath(
            path = bodyPath,
            brush = Brush.verticalGradient(
                colors = listOf(bodyHighlight, bodyPrimary, Color(0xFF12161D)),
                startY = cy - h * 0.34f,
                endY = cy + h * 0.16f
            )
        )
        drawPath(
            path = bodyPath,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.75f),
                    Color.White.copy(alpha = 0.45f),
                    accentColor.copy(alpha = 0.75f)
                )
            ),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        )

        // 3. Panoramic Tinted Cabin Glasshouse
        val cabinPath = Path().apply {
            moveTo(w * 0.28f, cy - h * 0.11f)
            lineTo(w * 0.39f, cy - h * 0.26f)
            quadraticTo(w * 0.53f, cy - h * 0.29f, w * 0.66f, cy - h * 0.24f)
            lineTo(w * 0.77f, cy - h * 0.11f)
            close()
        }
        drawPath(
            path = cabinPath,
            brush = Brush.verticalGradient(
                colors = if (isClimateActive) {
                    listOf(
                        NestHeatingOrange.copy(alpha = 0.55f),
                        Color(0xFF151922)
                    )
                } else {
                    listOf(
                        Color(0xFF3A4659).copy(alpha = 0.75f),
                        Color(0xFF12161F)
                    )
                }
            )
        )

        // B-Pillar divider
        drawLine(
            color = Color.White.copy(alpha = 0.22f),
            start = Offset(w * 0.53f, cy - h * 0.27f),
            end = Offset(w * 0.52f, cy - h * 0.11f),
            strokeWidth = 1.5.dp.toPx()
        )

        // 4. LED Signature Headlamps (Left) & Taillight Blade (Right)
        drawLine(
            color = Color(0xFFE0F7FA),
            start = Offset(w * 0.10f, cy - h * 0.03f),
            end = Offset(w * 0.17f, cy - h * 0.06f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFFF1744),
            start = Offset(w * 0.85f, cy - h * 0.06f),
            end = Offset(w * 0.91f, cy - h * 0.03f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )

        // 5. Front & Rear Multi-Spoke Alloy Wheels
        val wheelRadius = minOf(w * 0.10f, h * 0.20f)
        val wheelY = cy + h * 0.13f
        val wheelCenters = listOf(Offset(w * 0.25f, wheelY), Offset(w * 0.75f, wheelY))
        for (wc in wheelCenters) {
            drawCircle(
                color = Color(0xFF0D1015),
                radius = wheelRadius * 1.18f,
                center = wc
            )
            drawCircle(
                color = Color(0xFF1C2026),
                radius = wheelRadius,
                center = wc
            )
            drawCircle(
                color = accentColor.copy(alpha = 0.85f),
                radius = wheelRadius * 0.68f,
                center = wc,
                style = Stroke(width = 1.8.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = wheelRadius * 0.22f,
                center = wc
            )
        }

        // 6. Status Indicator Dot (Locked / Unlocked / Charging) above Roofline
        val statusColor = when {
            isCharging -> Color(0xFF00E5FF)
            isLocked -> accentColor
            else -> Color(0xFFFF5252)
        }
        drawCircle(
            color = statusColor.copy(alpha = 0.30f),
            radius = 7.dp.toPx(),
            center = Offset(w * 0.52f, cy - h * 0.38f)
        )
        drawCircle(
            color = statusColor,
            radius = 3.5.dp.toPx(),
            center = Offset(w * 0.52f, cy - h * 0.38f)
        )
    }
}

// ---------------------------------------------------------------------------
// 3D PRO BREEZE SMART TOWER FAN ILLUSTRATION (Point 12)
// - Features animated airflow waves, 70° oscillation base arc, and digital speed LED badge
// ---------------------------------------------------------------------------
@Composable
internal fun TowerFanHeroIllustration(
    isActive: Boolean,
    speedLevel: Int,
    isOscillating: Boolean,
    isIonizerActive: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "TowerFanAirflowAnim")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (1400 - (speedLevel.coerceIn(1, 6) * 160)).coerceAtLeast(380),
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.44f

            // 1. Ambient Halo Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = if (isActive) 0.28f else 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(cx, h * 0.48f),
                    radius = w * 0.56f
                ),
                radius = w * 0.56f,
                center = Offset(cx, h * 0.48f)
            )

            // 2. Cylindrical Tower Body
            val towerW = w * 0.36f
            val towerH = h * 0.72f
            val towerLeft = cx - towerW / 2f
            val towerTop = h * 0.10f

            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF383E4C),
                        Color(0xFF232730),
                        Color(0xFF171A21)
                    ),
                    startX = towerLeft,
                    endX = towerLeft + towerW
                ),
                topLeft = Offset(towerLeft, towerTop),
                size = Size(towerW, towerH),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
            )
            drawRoundRect(
                color = if (isActive) accentColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.22f),
                topLeft = Offset(towerLeft, towerTop),
                size = Size(towerW, towerH),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                style = Stroke(width = 1.4.dp.toPx())
            )

            // 3. Vertical Air Grille Louvers
            val grilleTop = towerTop + towerH * 0.22f
            val grilleBottom = towerTop + towerH * 0.86f
            val slatCount = 11
            for (i in 0..slatCount) {
                val y = grilleTop + (grilleBottom - grilleTop) * (i / slatCount.toFloat())
                drawLine(
                    color = if (isActive) accentColor.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.14f),
                    start = Offset(towerLeft + towerW * 0.18f, y),
                    end = Offset(towerLeft + towerW * 0.82f, y),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 4. Stabilizer Base Pedestal & 70° Oscillation Arc
            val baseW = w * 0.58f
            val baseH = h * 0.08f
            val baseLeft = cx - baseW / 2f
            val baseTop = towerTop + towerH - 2.dp.toPx()

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF2F3542), Color(0xFF15181F))
                ),
                topLeft = Offset(baseLeft, baseTop),
                size = Size(baseW, baseH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )

            if (isActive && isOscillating) {
                val arcW = baseW * 1.12f
                val arcH = h * 0.10f
                drawArc(
                    color = accentColor,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(cx - arcW / 2f, baseTop + baseH * 0.35f),
                    size = Size(arcW, arcH),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 5. Animated Airflow Waves (Right of Tower Grille)
            if (isActive) {
                val waveStartX = towerLeft + towerW + 4.dp.toPx()
                for (wIdx in 0..2) {
                    val yCenter = towerTop + towerH * (0.34f + wIdx * 0.20f)
                    val offsetShift = ((wavePhase + wIdx * 0.28f) % 1f) * (w * 0.18f)
                    val alpha = (1f - ((wavePhase + wIdx * 0.28f) % 1f)).coerceIn(0.20f, 0.85f)
                    val wavePath = Path().apply {
                        moveTo(waveStartX + offsetShift * 0.4f, yCenter)
                        quadraticTo(
                            waveStartX + offsetShift * 0.7f + 6.dp.toPx(),
                            yCenter - 4.dp.toPx(),
                            waveStartX + offsetShift + 14.dp.toPx(),
                            yCenter
                        )
                    }
                    drawPath(
                        path = wavePath,
                        color = if (isIonizerActive && wIdx == 1) GhAccentGreen.copy(alpha = alpha) else accentColor.copy(alpha = alpha),
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Top Digital LED Speed Badge on the Tower Fan Crown
        Surface(
            color = Color(0xFF101319).copy(alpha = 0.90f),
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(
                0.8.dp,
                if (isActive) accentColor.copy(alpha = 0.7f) else GhCardBorder
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(x = (-5).dp, y = 16.dp)
        ) {
            Text(
                text = if (isActive) "0$speedLevel" else "--",
                color = if (isActive) accentColor else GhTextSecondary,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = GhFont,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 3D ROBOTIC VACUUM ILLUSTRATION FOR THE ROBOVAC HERO CARD
// ---------------------------------------------------------------------------
@Composable
internal fun RoboticVacuumHeroIllustration(
    isCleaning: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = if (isCleaning) 0.32f else 0.12f),
                    Color.Transparent
                ),
                center = center,
                radius = r
            ),
            radius = r,
            center = center
        )

        val bodyRadius = r * 0.84f
        drawCircle(
            color = Color(0xFF15171B),
            radius = bodyRadius + 4.dp.toPx(),
            center = center
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF454B58),
                    Color(0xFF282C34),
                    Color(0xFF191C22)
                ),
                center = Offset(center.x, center.y - bodyRadius * 0.25f),
                radius = bodyRadius * 1.25f
            ),
            radius = bodyRadius,
            center = center
        )

        val bumperArcR = bodyRadius * 0.90f
        drawArc(
            color = if (isCleaning) accentColor else Color.White.copy(alpha = 0.25f),
            startAngle = 210f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(center.x - bumperArcR, center.y - bumperArcR),
            size = Size(bumperArcR * 2f, bumperArcR * 2f),
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
        )

        val innerRingR = bodyRadius * 0.72f
        drawCircle(
            color = Color.White.copy(alpha = 0.12f),
            radius = innerRingR,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )

        val turretR = bodyRadius * 0.34f
        val turretCenter = Offset(center.x, center.y - bodyRadius * 0.08f)
        drawCircle(
            color = Color.Black.copy(alpha = 0.55f),
            radius = turretR + 3.dp.toPx(),
            center = Offset(turretCenter.x, turretCenter.y + 2.dp.toPx())
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF3B404C),
                    Color(0xFF1F2229)
                ),
                center = turretCenter,
                radius = turretR
            ),
            radius = turretR,
            center = turretCenter
        )
        drawCircle(
            color = if (isCleaning) accentColor else Color(0xFF00C6FF).copy(alpha = 0.65f),
            radius = turretR,
            center = turretCenter,
            style = Stroke(width = 2.dp.toPx())
        )

        val ledCenter = Offset(center.x, center.y + bodyRadius * 0.46f)
        drawCircle(
            color = accentColor.copy(alpha = 0.35f),
            radius = 8.dp.toPx(),
            center = ledCenter
        )
        drawCircle(
            color = if (isCleaning) accentColor else Color.White.copy(alpha = 0.75f),
            radius = 4.dp.toPx(),
            center = ledCenter
        )
    }
}
