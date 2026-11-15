package io.github.silleruss.drivy

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

private val Ink = Color(0xFF151622)
private val Cream = Color(0xFFFFF1E2)
private val Mint = Color(0xFF9DECC4)
private val Muted = Color(0xFFD5C7C2)
private val Panel = Color(0xFF382D30)
private val PanelBorder = Color(0xFF765950)
private val Warning = Color(0xFFFFD4A8)

@Composable
fun CompanionScreen(totalKm: Double, experience: Double, drivingScore: Int?, restricted: Boolean,
                    dataStatus: String, storageStatus: String, companion: Companion,
                    speedPermissionGranted: Boolean = true, onGame: () -> Unit = {},
                    reportContent: @Composable () -> Unit = {},
                    settings: @Composable () -> Unit = {}) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Ink, surface = Panel,
        onPrimary = Ink, onBackground = Cream, onSurface = Cream)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            if (restricted) {
                Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("D R I V Y", color = Mint, fontSize = 20.sp)
                    Spacer(Modifier.height(28.dp))
                    Text("주차 후에 다시 만나요", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text("${companion.displayName}가 쉬고 있어요.\n차량에서 이용 가능한 상태인지 확인하고 있어요.", color = Muted, fontSize = 18.sp)
                }
            } else {
                CompanionContent(totalKm, experience, drivingScore, dataStatus, storageStatus, companion, speedPermissionGranted, onGame, reportContent, settings)
            }
        }
    }
}

@Composable
private fun CompanionContent(totalKm: Double, experience: Double, drivingScore: Int?, dataStatus: String,
                             storageStatus: String, companion: Companion, speedPermissionGranted: Boolean, onGame: () -> Unit,
                             reportContent: @Composable () -> Unit,
                             settings: @Composable () -> Unit) {
    var greeting by remember(companion) { mutableStateOf(false) }
    var details by remember(companion) { mutableStateOf(false) }
    LaunchedEffect(greeting, companion) { if (greeting) { delay(3000); greeting = false } }
    val stage = DistanceGrowth.stageFor(experience)
    val stageName = companion.forms[stage].name
    val fontScale = LocalDensity.current.fontScale
    val storageNotice = when (storageStatus) {
        "corrupt" -> "기록을 복원하지 못했어요 · 성장 중지 · 원본 기록은 보관하고 있어요"
        "save_failed" -> "저장 실패 · 진행을 유지하며 다시 시도해요"
        "recovered_backup" -> "이전 저장 기록에서 복원했어요"
        "imported_unity" -> "이전 앱의 동행 기록을 이어왔어요"
        else -> null
    }
    val connectionNotice = when (dataStatus) {
        "차량에 연결할 수 없어요 · 성장은 간직하고 있어요",
        "차량 데이터를 확인할 수 없어요 · 성장은 간직하고 있어요" -> "차량 연결 확인 필요 · 거리 반영 대기"
        else -> null
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = minOf(maxWidth, 1120.dp) / fontScale >= 840.dp
        Column(Modifier.align(Alignment.Center).widthIn(max = 1120.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("DRIVY", color = Mint, fontSize = 22.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { details = true },
                    modifier = Modifier.heightIn(min = 76.dp).widthIn(min = 112.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("자세히", fontSize = 20.sp)
                }
            }
            for (notice in listOfNotNull(storageNotice, connectionNotice)) {
                Text(notice, color = Warning, fontSize = 20.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            OutlinedButton(onClick = onGame, modifier = Modifier.widthIn(min = 200.dp).heightIn(min = 76.dp),
                shape = RoundedCornerShape(20.dp)) { Text("드라이비 런", fontSize = 22.sp) }
            val friend: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompanionSprite(greeting, companion, stage, "$stageName · ${stage + 1}단계",
                        Modifier.widthIn(max = if (wide) 480.dp else 368.dp).fillMaxWidth().aspectRatio(1f)
                            .background(Brush.radialGradient(listOf(Color(0xFF704638), Ink))))
                    Text(stageName, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    Text("${stage + 1}단계", color = Muted, fontSize = 20.sp)
                    Button(onClick = { greeting = true },
                        modifier = Modifier.widthIn(min = 200.dp).heightIn(min = 76.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (greeting) "반가워요!" else "인사하기", fontSize = 22.sp)
                    }
                }
            }
            val progress: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    reportContent()
                    Surface(shape = RoundedCornerShape(24.dp), color = Panel,
                        modifier = Modifier.border(1.dp, PanelBorder, RoundedCornerShape(24.dp))) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("성장 경험치", color = Muted, fontSize = 20.sp)
                            Text("${formatXp(experience)} XP", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                            val target = if (stage == 0) DistanceGrowth.SECOND_STAGE_XP else DistanceGrowth.THIRD_STAGE_XP
                            val start = if (stage == 0) 0.0 else DistanceGrowth.SECOND_STAGE_XP
                            LinearProgressIndicator(progress = {
                                if (stage == 2) 1f else ((experience - start) / (target - start)).toFloat().coerceIn(0f, 1f)
                            }, modifier = Modifier.fillMaxWidth().height(14.dp).semantics {
                                contentDescription = "진화 진행도"
                            }, color = Mint, trackColor = Ink, gapSize = 0.dp, drawStopIndicator = {})
                            Text(if (stage == 2) "최종 진화 · 우리의 여정은 계속돼요" else
                                "${companion.forms[stage + 1].name}까지 ${formatXp(target - experience)} XP",
                                color = Cream, fontSize = 20.sp)
                        }
                    }
                    val scoreCard: @Composable (Modifier) -> Unit = { card ->
                        MetricCard("운전 습관", drivingScore?.let { "$it / 100" } ?: "측정 대기",
                            if (speedPermissionGranted) "가감속 기준" else "자세히에서 속도 권한 요청", card,
                            if (drivingScore != null && drivingScore < 50) Warning else Cream)
                    }
                    val distanceCard: @Composable (Modifier) -> Unit = { card ->
                        MetricCard("함께한 거리", String.format(Locale.ROOT, "%.2f km", totalKm), null, card)
                    }
                    if (wide) {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            scoreCard(Modifier.weight(1f).fillMaxHeight())
                            distanceCard(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        scoreCard(Modifier.fillMaxWidth())
                        distanceCard(Modifier.fillMaxWidth())
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    friend(Modifier.weight(1f))
                    progress(Modifier.weight(1f))
                }
            } else {
                friend(Modifier.fillMaxWidth())
                progress(Modifier.fillMaxWidth())
            }
        }
    }
    if (details) {
        Dialog(onDismissRequest = { details = false }) {
            Surface(shape = MaterialTheme.shapes.large, color = Panel, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.9f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("동행 정보", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    val scroll = rememberScrollState()
                    Text(if (scroll.value < scroll.maxValue) "아래로 스크롤하면 더 볼 수 있어요 ↓" else if (scroll.maxValue > 0) "위로 스크롤하면 이전 내용을 볼 수 있어요 ↑" else "모든 내용이 표시돼요", fontSize = 16.sp)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(companion.concept, color = Cream, fontSize = 20.sp)
                    settings()
                    HorizontalDivider(color = Muted.copy(alpha = .25f))
                    Text("경험치와 운전 점수", color = Mint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("75점 초과 보너스 · 기본 경험치는 차감하지 않아요", color = Cream, fontSize = 20.sp)
                    Text("평가할 데이터가 없으면 거리 경험치만 반영해요. 급가속·급감속 기준이며 과속·신호 위반은 평가하지 않아요.",
                        color = Muted, fontSize = 20.sp)
                    Text("차량 연결", color = Mint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(dataStatus, color = Cream, fontSize = 20.sp)
                    Text("저장 상태", color = Mint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(storageNotice ?: "친구와 경험치를 이 기기에 저장해요", color = Cream, fontSize = 20.sp)
                    }
                    TextButton(onClick = { details = false }, modifier = Modifier.align(Alignment.End).heightIn(min = 56.dp)) {
                        Text("닫기", fontSize = 22.sp)
                    }
                }
            }
        }
    }
}

private fun formatXp(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

@Composable
private fun MetricCard(label: String, value: String, caption: String?, modifier: Modifier, valueColor: Color = Cream) {
    Surface(modifier.border(1.dp, PanelBorder, RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp), color = Panel) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Muted, fontSize = 20.sp)
            Text(value, color = valueColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (caption != null) Text(caption, color = Muted, fontSize = 18.sp)
        }
    }
}

@Composable
internal fun CompanionSprite(greeting: Boolean, companion: Companion, stage: Int, description: String, modifier: Modifier,
                             contentPadding: Dp = 16.dp) {
    val transition = rememberInfiniteTransition(label = "companion")
    val phase = transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(4800, easing = LinearEasing), RepeatMode.Restart), label = "motion")
    val growth by animateFloatAsState(.8f + stage * .1f, tween(600), label = "growth")
    val atlas = ImageBitmap.imageResource(companion.forms[stage].sprite)
    Crossfade(targetState = stage, animationSpec = tween(600), label = "evolution",
        modifier = modifier.semantics { contentDescription = description }) { column ->
        Canvas(Modifier.fillMaxSize().padding(contentPadding).graphicsLayer {
            val pose = companionPose(companion, phase.value, greeting)
            translationY = pose.lift.dp.toPx()
            rotationZ = pose.tilt
            scaleX = growth * (1f - pose.stretch / 2)
            scaleY = growth * (1f + pose.stretch)
            transformOrigin = TransformOrigin(.5f, .9f)
        }) {
            val pose = companionPose(companion, phase.value, greeting)
            val cell = atlas.width / 3
            drawImage(atlas, srcOffset = IntOffset(column * cell, pose.frame * cell),
                srcSize = IntSize(cell, cell), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            val tint = when (companion) {
                Companion.MOKA, Companion.TORI -> Color(0xFFA6C77B)
                Companion.NURI -> Color(0xFF8BD7FF)
                Companion.PIKO -> Color(0xFFFFB25C)
                Companion.NERU -> Color(0xFFFFD98A)
                Companion.RAPI -> Color(0xFFFFB5C4)
            }
            repeat(if (greeting) 5 else 3) { index ->
                val travel = (phase.value + index / 5f) % 1f
                val center = Offset(size.width * (if (index % 2 == 0) .1f else .9f),
                    size.height * (.85f - travel * .7f))
                val radius = (if (greeting) 3.5f else 2.5f).dp.toPx()
                val color = tint.copy(alpha = kotlin.math.sin(travel * Math.PI).toFloat() * .7f)
                when (companion) {
                    Companion.NURI -> drawCircle(color, radius, center, style = Stroke(1.dp.toPx()))
                    Companion.NERU -> {
                        drawLine(color, center - Offset(radius, 0f), center + Offset(radius, 0f), 1.dp.toPx())
                        drawLine(color, center - Offset(0f, radius), center + Offset(0f, radius), 1.dp.toPx())
                    }
                    Companion.MOKA, Companion.RAPI -> drawOval(color, center - Offset(radius, radius / 2),
                        androidx.compose.ui.geometry.Size(radius * 2, radius))
                    Companion.PIKO, Companion.TORI -> drawCircle(color, radius / 2, center)
                }
            }
        }
    }
}
