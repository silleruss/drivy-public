package io.github.silleruss.drivy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RunnerScreen(companion: Companion, stage: Int, randomSeed: Int? = null, exit: () -> Unit) {
    val context = LocalContext.current
    val scores = remember { RunnerScores(context.filesDir, "runner-scores-pattern-v2.json") }
    val motion by produceState<Result<RunnerSprites>?>(null, companion, stage) {
        value = null
        try { value = Result.success(withContext(Dispatchers.Default) { loadRunnerSprites(context.assets, companion, stage) }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { value = Result.failure(error) }
    }
    val sprites = motion?.getOrNull()
    var game by remember { mutableStateOf<RunnerGame?>(null) }
    val frame = remember { mutableIntStateOf(0) }
    var records by remember { mutableStateOf<List<RunnerRecord>>(emptyList()) }
    var bestScore by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val jumpInput = remember { MutableInteractionSource() }
    var physicalJump by remember { mutableStateOf(false) }
    var spaceDown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(game) { spaceDown = false; if (game != null) focus.requestFocus() }
    LaunchedEffect(jumpInput, game) {
        jumpInput.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> { physicalJump = true; game?.jump(); frame.intValue++ }
                is PressInteraction.Release -> game?.releaseJump()
                is PressInteraction.Cancel -> { game?.releaseJump(); physicalJump = false }
            }
        }
    }
    LaunchedEffect(scores) {
        try { bestScore = withContext(Dispatchers.IO) { scores.load().firstOrNull()?.score } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bestScore = null }
    }
    LaunchedEffect(game) {
        val current = game ?: return@LaunchedEffect
        var previous = 0L
        while (!current.over) {
            withFrameNanos { now ->
                if (previous != 0L) current.advance((now - previous) / 1_000_000_000.0)
                previous = now
                frame.intValue++
            }
        }
        saving = true
        try { records = withContext(Dispatchers.IO) { scores.add(RunnerRecord(current.score, companion)) }; bestScore = records.firstOrNull()?.score }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            try { records = withContext(Dispatchers.IO) { scores.load() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { records = emptyList() }
            error = true
        }
        finally { saving = false }
    }
    // Only HUD changes recompose the layout; the Canvas observes every display frame directly.
    val hud by remember { derivedStateOf {
        frame.intValue
        game?.let { RunnerHud(it.score, it.coins, it.stage, it.stagePassed, it.over, it.theme) }
    } }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFAEDBB5), onPrimary = Color(0xFF102127),
        background = Color(0xFF102127), surface = Color(0xFF203238))) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF102127))
            .onPreviewKeyEvent { event ->
                val current = game
                if (event.key != Key.Spacebar || current == null || current.over) false
                else when (event.type) {
                    KeyEventType.KeyDown -> { if (!spaceDown) { spaceDown = true; current.jump(); frame.intValue++ }; true }
                    KeyEventType.KeyUp -> {
                        // shortcut: emulator text taps last 0–1ms and lose hold length, use full jumps until physical timing is preserved.
                        if (spaceDown && event.nativeKeyEvent.eventTime - event.nativeKeyEvent.downTime >= 2) current.releaseJump()
                        spaceDown = false
                        true
                    }
                    else -> false
                }
            }.focusRequester(focus).onFocusChanged { if (!it.hasFocus && spaceDown) { game?.releaseJump(); spaceDown = false } }.focusable()) {
            LaunchedEffect(maxWidth, maxHeight) { game = null }
            val playerHalfWidth = (minOf(maxWidth.value * .15f, maxHeight.value * .24f) * .55f / maxWidth.value / 4).toDouble()
            RunnerWorld(companion, stage, game, frame, sprites, Modifier.fillMaxSize())
            if (hud != null && hud?.over == false) Column(Modifier.align(Alignment.TopEnd).padding(16.dp).widthIn(max = 340.dp)
                .heightIn(max = maxHeight * 0.5f).verticalScroll(rememberScrollState())
                .background(Color(0xF20C2A1D), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFFD6BF73), RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("스테이지 ${hud?.stage ?: 1} · ${listOf("초록 언덕", "노을 사막", "별빛 길")[hud?.theme ?: 0]}", color = Color(0xFFCCE8B4), fontSize = 16.sp)
                Text("점수 ${hud?.score ?: 0}", color = Color(0xFFFFF2D0), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = Color(0xFF8B8650))
                Text("✦  코인 ${hud?.coins ?: 0}", color = Color(0xFFFFD578), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val progress = hud?.passed ?: 0
                Text("진행 ${"●".repeat(progress.coerceIn(0, 5))}${"○".repeat(5 - progress.coerceIn(0, 5))}  $progress / 5",
                    color = Color(0xFFDAEAB6), fontSize = 16.sp)
                if (hud?.over != true) bestScore?.let { Text("최고 기록 ${it}점", color = Color(0xFFD4D8AA), fontSize = 14.sp) }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                if (hud != null && hud?.over == false) {
                    Button(onClick = { if (!physicalJump) { game?.jump(); game?.releaseJump(); frame.intValue++ }; physicalJump = false },
                        interactionSource = jumpInput, modifier = Modifier.weight(1f).heightIn(min = 72.dp)) {
                        Text("점프", fontSize = 24.sp)
                    }
                }
                OutlinedButton(onClick = exit, modifier = Modifier.heightIn(min = 72.dp)
                    .background(Color(0xDD102127), MaterialTheme.shapes.extraLarge)) { Text("나가기", color = Color.White, fontSize = 18.sp) }
            }
            if (hud == null || hud?.over == true) {
                Column(Modifier.align(Alignment.Center).padding(top = 112.dp, bottom = 100.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 480.dp).fillMaxWidth().background(Color(0xF0102127), MaterialTheme.shapes.large)
                    .verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (hud == null) "드라이비 런" else "게임 종료", color = Color.White, fontSize = 24.sp)
                    if (hud == null) Text("점프를 길게 누르면 더 높이 뛰어요. 공중에서 한 번 더 누르면 2단 점프!\n생존 1점/초 · 장애물 +5 · 코인 +10\n장애물 5개를 넘으면 다음 스테이지!", color = Color.White, fontSize = 16.sp)
                    Button(onClick = { error = false; game = RunnerGame(playerHalfWidth, randomSeed?.let { kotlin.random.Random(it) } ?: kotlin.random.Random.Default) }, enabled = !saving && sprites != null, modifier = Modifier.heightIn(min = 64.dp)) { Text(if (hud == null) "시작" else "다시 시작", fontSize = 20.sp) }
                    if (sprites == null) Text(if (motion == null) "캐릭터 모션을 준비하고 있어요" else "캐릭터 모션을 불러오지 못했어요. 게임을 다시 열어주세요.", color = Color.White)
                    if (hud?.over == true) {
                        Text("개인 랭킹", color = Color.White, fontSize = 20.sp)
                        if (saving) Text("기록을 저장하고 있어요", color = Color.White)
                        records.firstOrNull()?.let { Text("최고 기록 ${it.score}점", color = Color.White, fontSize = 18.sp) }
                        records.forEachIndexed { index, record -> Text("${index + 1}위  ${record.score}점 · ${record.companion.displayName}", color = Color.White, fontSize = 18.sp) }
                        if (error) Text("기록을 저장하지 못했어요. 기존 기록은 보존됩니다.", color = Color(0xFFFFD4A8), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

private data class RunnerHud(val score: Int, val coins: Int, val stage: Int, val passed: Int, val over: Boolean, val theme: Int)

@Composable
private fun RunnerWorld(companion: Companion, growth: Int, game: RunnerGame?, frame: State<Int>, sprites: RunnerSprites?, modifier: Modifier) {
    val atlas = ImageBitmap.imageResource(companion.forms[growth].sprite)
    val scenery = rememberRunnerScenery()
    val fontScale = LocalDensity.current.fontScale
    val referenceBounds = remember(atlas, growth) {
        val cell = atlas.width / 3
        val pixels = IntArray(atlas.width * atlas.height)
        atlas.readPixels(pixels)
        var left = cell; var right = 0; var top = cell; var bottom = 0
        for (y in 0 until cell) for (x in 0 until cell) {
            if (pixels[y * atlas.width + growth * cell + x] ushr 24 >= 128) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        (right - left + 1).toFloat() / cell to (bottom - top + 1).toFloat() / cell
    }
    val tint = when (companion) {
        Companion.MOKA -> Color(0xFFA6C77B)
        Companion.NURI -> Color(0xFF8BD7FF)
        Companion.PIKO -> Color(0xFFFFB25C)
        Companion.NERU -> Color(0xFFFFD98A)
        Companion.TORI -> Color(0xFFFFBC85)
        Companion.RAPI -> Color(0xFFFFB5C4)
    }
    Canvas(modifier.testTag("runner-world").semantics { contentDescription = "${companion.displayName} 달리기" }) {
        frame.value // Mutable model fields alone do not invalidate a Compose draw pass.
        val t = (game?.elapsed ?: 0.0).toFloat()
        val level = (game?.theme ?: 0) + 1
        val w = size.width
        val h = size.height
        val ground = minOf(h * .76f, h - 108.dp.toPx())
        val unit = h * .66f
        if (scenery != null) drawRunnerBackground(scenery, level, (game?.distance ?: 0.0).toFloat(), ground)
        else drawRect(Color(0xFF183D45))
        game?.let { current ->
            val ox = current.obstacle.toFloat() * w
            val oh = current.obstacleHeight.toFloat() * unit
            val ow = current.obstacleWidth.toFloat() * w
            if (scenery != null) drawRunnerObstacle(scenery, current.obstacleKind + 1, Offset(ox, ground - oh), Size(ow, oh))
            else drawRect(Color(0xFFBB6448), Offset(ox, ground - oh), Size(ow, oh))
            current.secondOffset?.let { offset ->
                val second = Offset((current.obstacle + offset).toFloat() * w, ground - .07f * unit)
                val secondSize = Size(.04f * w, .07f * unit)
                if (scenery != null) drawRunnerObstacle(scenery, (current.obstacleKind + 1) % 3 + 1, second, secondSize)
                else drawRect(Color(0xFFBB6448), second, secondSize)
            }
            for (coin in current.coinPattern) if (!coin.collected) {
                val center = Offset((current.coinX + coin.offset).toFloat() * w, ground - coin.height.toFloat() * unit)
                val radius = minOf(w * .022f, h * .035f)
                if (scenery != null) drawRunnerCoin(scenery, center, radius, t)
                else drawCircle(Color(0xFFFFD578), radius, center)
            }
        }

        val airborne = (game?.height ?: 0.0) > 0.0 || (game?.verticalVelocity ?: 0.0) > 0.0
        val running = game != null && !game.over && !airborne
        val wide = minOf(w / density, 1120f) / fontScale >= 840f
        val referenceSize = (if (wide) 448.dp else 336.dp).toPx() * (.8f + growth * .1f)
        val sprite = referenceSize * referenceBounds.second * .5f
        val x = w * .225f
        val y = ground - (game?.height ?: 0.0).toFloat() * unit
        drawOval(Color.Black.copy(alpha = .24f), Offset(x - sprite * .30f, ground - 4.dp.toPx()), Size(sprite * .60f, 9.dp.toPx()))
        if (sprites != null) {
            val index = if (running || airborne) runnerFrame(game?.runElapsed ?: 0.0, airborne, game?.verticalVelocity ?: 0.0) else 0
            val frames = if (airborne) sprites.jumping else sprites.running
            run {
                val pose = frames[index]
                val factor = sprite / pose.referenceHeight
                val width = pose.image.width * factor
                val height = pose.image.height * factor
                drawImage(pose.image,
                    dstOffset = IntOffset((x - pose.originX * factor).toInt(), (y - pose.groundY * factor).toInt()),
                    dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)))

            }
        }
        if (running || airborne) repeat(4) { i ->
            val phase = (t * 2 + i * .25f) % 1f
            drawCircle(tint.copy(alpha = (1 - phase) * .5f), (1 - phase) * 3.dp.toPx(), Offset(x - sprite * (.3f + phase * .45f), y - sprite * (.05f + phase * .12f)))
        }
    }
}
