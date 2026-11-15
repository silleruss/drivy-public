package io.github.silleruss.drivy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun testProgress(xp: String, km: String, penalty: String, companion: Companion): PetProgress =
    PetProgress(km.toDouble(), xp.toDouble(), companion, penalty.toDouble())

/** Development controls use the same validation and persistence as normal progress. */
@Composable
internal fun TestControls(progress: PetProgress, storage: String, writable: Boolean,
    onApply: (PetProgress) -> Unit, onGame: (Int) -> Unit, onWindow: () -> Unit, onReload: () -> Unit, vehicleSamples: Map<String, String> = emptyMap()) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputDistance by remember { mutableStateOf("0.05") }
    var inputSeconds by remember { mutableStateOf("10") }
    var xp by remember { mutableStateOf(progress.experience.toString()) }
    var km by remember { mutableStateOf(progress.totalKm.toString()) }
    var penalty by remember { mutableStateOf(progress.drivingPenalty.toString()) }
    var companion by remember { mutableStateOf(progress.companion) }
    var seed by remember { mutableStateOf("1") }
    var score by remember { mutableStateOf("100") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("") }
    var showCompanions by remember { mutableStateOf(false) }
    BackHandler(enabled = section.isNotEmpty()) { section = ""; result = "" }
    fun select(title: String) { section = title; result = ""; showCompanions = false }
    fun apply(xpValue: String, kmValue: String, penaltyValue: String, companionValue: Companion) {
        try { onApply(testProgress(xpValue, kmValue, penaltyValue, companionValue)); result = "적용 요청 완료 · 저장 상태는 상단에서 확인하세요." }
        catch (_: IllegalArgumentException) { result = "유효 범위: XP 0~20억, 거리 0~1천만 km, 감점 0~100. 유한한 숫자를 입력하세요." }
    }
    fun ranking(add: Boolean) {
        val value = score.toIntOrNull()
        if (add && (value == null || value < 0)) { result = "점수는 0~2147483647 정수로 입력하세요."; return }
        busy = true
        scope.launch {
            try {
                val records = withContext(Dispatchers.IO) {
                    val store = RunnerScores(context.filesDir, "runner-scores-pattern-v2.json")
                    if (add) store.add(RunnerRecord(value!!, companion)) else store.load()
                }
                result = if (records.isEmpty()) "랭킹 없음" else records.mapIndexed { i, r -> "${i + 1}. ${r.companion.displayName} ${r.score}점" }.joinToString("\n")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { result = "랭킹 읽기/저장 실패 · 기존 파일을 확인하세요." }
            finally { busy = false }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9DECC4), surface = Color(0xFF141420),
        onSurface = Color(0xFFFFF1E2), onPrimary = Color(0xFF141420),
        secondaryContainer = Color(0xFF382D30), onSecondaryContainer = Color(0xFFFFF1E2)),
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp))) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                key(section) {
                Column(Modifier.widthIn(max = 960.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (section.isNotEmpty()) OutlinedButton(onClick = { select("") }) { Text("‹ 테스트 목록") }
                        Text(section.ifEmpty { "테스트" }, fontSize = 28.sp)
                    }
                    if (section.isEmpty()) Text("확인할 기능을 선택하세요.")
                    else Text("${progress.companion.displayName} · ${progress.experience} XP · ${progress.totalKm} km · 저장 $storage",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TestSection("실시간 차량 데이터", section, ::select) {
                        Text("SDK·AAOS 수신값 · 현재 검증 환경: 에뮬레이터. 아래 값은 앱 내 합성 입력과 별개입니다.")
                        Text("Pleos Virtual Console에서 누적 거리·도어를 변경한 뒤 수신 시각과 값을 확인하세요. 주행 제한 중에는 이 화면도 숨겨집니다.")
                        if (vehicleSamples.isEmpty()) Text("수신 대기 · 값이 없다고 차량 상태가 정상인 것은 아닙니다.")
                        vehicleSamples.forEach { (name, value) ->
                            Text(name, color = MaterialTheme.colorScheme.primary)
                            Text("마지막 수신 $value")
                        }
                        Text("마지막 수신값이며 현재 상태를 보장하지 않습니다. distance_km: km, speed: km/h. unsupported·permission_denied·timeout은 수신 실패 상태입니다.")
                    }
                    TestSection("차량 데이터 값 설정", section, ::select) {
                        Text("저장된 차량 관련 값 설정")
                        TestNumber("누적 거리 km", km) { km = it }
                        TestNumber("운전 감점", penalty) { penalty = it }
                        Button(onClick = { apply(progress.experience.toString(), km, penalty, progress.companion) }, enabled = writable) { Text("차량 데이터 값 적용") }
                        Text("이동 거리와 시간으로 성장 확인")
                        Text("출처: 직접 입력한 합성 데이터 · 실제 SDK 검사 아님 · 현재 친구의 기록에 저장하지 않습니다.")
                        TestNumber("이동 거리 km (0~100)", inputDistance) { inputDistance = it }
                        TestNumber("이동 시간 초 (1~1800)", inputSeconds) { inputSeconds = it }
                        Button(onClick = {
                            result = try {
                                val simulated = syntheticDrive(inputDistance.toDouble(), inputSeconds.toDouble())
                                if (simulated.totalKm == 0.0 && inputDistance.toDouble() > 0)
                                    "${companion.displayName}: 입력한 거리 변화가 시간에 비해 너무 커서 반영하지 않았어요."
                                else "${companion.displayName}: 입력한 이동 거리 %.2f km에 대한 기본 경험치는 +%.1f XP예요.\n실제 저장 기록은 바뀌지 않았어요.".format(java.util.Locale.KOREA, simulated.totalKm, simulated.experience)
                            } catch (_: IllegalArgumentException) { "유한한 숫자로 거리 0~100km, 시간 1~1800초를 입력하세요." }
                        }, modifier = Modifier.heightIn(min = 76.dp)) { Text("입력값으로 성장 확인") }
                    }
                    TestSection("성장과 캐릭터", section, ::select) {
                        TestNumber("경험치 XP", xp) { xp = it }
                        Box {
                            OutlinedButton(onClick = { showCompanions = true }) { Text("캐릭터: ${companion.displayName}") }
                            DropdownMenu(expanded = showCompanions, onDismissRequest = { showCompanions = false }) {
                                Companion.entries.forEach { item ->
                                    DropdownMenuItem(text = { Text(item.displayName) }, onClick = { companion = item; showCompanions = false })
                                }
                            }
                        }
                        Button(onClick = { apply(xp, progress.totalKm.toString(), progress.drivingPenalty.toString(), companion) }, enabled = writable) { Text("입력값과 캐릭터 적용") }
                        Text("경험치별 진화 단계: 0 XP 이상 1단계 · 50,000 XP 이상 2단계 · 300,000 XP 이상 3단계")
                        listOf(0.0, DistanceGrowth.SECOND_STAGE_XP - 1, DistanceGrowth.SECOND_STAGE_XP,
                            DistanceGrowth.THIRD_STAGE_XP - 1, DistanceGrowth.THIRD_STAGE_XP).forEach { value ->
                            OutlinedButton(onClick = { xp = value.toString(); apply(xp, progress.totalKm.toString(), progress.drivingPenalty.toString(), companion) }, enabled = writable) { Text("$value XP 적용") }
                        }
                    }
                    TestSection("드라이비 성장 기록 저장과 복원", section, ::select) {
                        Text("경험치, 누적 거리, 운전 감점과 선택한 캐릭터의 저장 및 복원을 확인합니다. 차량 데이터 초기화는 하지 않습니다.")
                        Button(onClick = onReload) { Text("성장 기록 저장 후 화면 재생성") }
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                try { result = withContext(Dispatchers.IO) { testStorageScenario(context.cacheDir) } }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (error: Exception) { result = "실패: ${error.message}" }
                                finally { busy = false }
                            }
                        }, enabled = !busy) { Text("임시 성장 기록의 백업 복구·손상 검사") }
                    }
                    TestSection("드라이비 런", section, ::select) {
                        TestNumber("배치 재현 시드 (정수)", seed) { seed = it }
                        Button(onClick = {
                            val value = seed.toIntOrNull()
                            if (value == null) result = "시드는 32비트 정수로 입력하세요." else onGame(value)
                        }, enabled = writable && storage != "new") { Text("게임 시작") }
                        Text("랭킹 캐릭터: ${companion.displayName} · 성장과 캐릭터에서 선택")
                        TestNumber("랭킹에 추가할 점수", score) { score = it }
                        Button(onClick = { ranking(true) }, enabled = !busy) { Text("선택 캐릭터의 점수 추가") }
                        OutlinedButton(onClick = { ranking(false) }, enabled = !busy) { Text("랭킹 조회") }
                    }
                    TestSection("화면 위 드라이비", section, ::select) {
                        Text("권한과 위치는 드라이비 화면의 상세 설정에서 변경합니다.")
                        Button(onClick = onWindow) { Text("드라이비 띄우기") }
                    }
                    TestSection("주행 데이터 처리 검사", section, ::select) {
                        Text("미리 정한 가상 주행 데이터로 거리 증가에 따른 성장, 비정상 거리 급증 거부, 재연결 후 기준점 설정, 운전 점수 만료를 검사합니다. 실제 차량 데이터와 저장 기록은 사용하지 않습니다.")
                        Button(onClick = { result = try { testDrivingScenario() } catch (error: Exception) { "실패: ${error.message}" } }) {
                            Text("가상 주행 데이터로 검사 실행")
                        }
                    }
                    if (result.isNotEmpty()) Surface(color = Color(0xFF24382F), shape = MaterialTheme.shapes.medium) {
                        Text(result, modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (section.isEmpty()) Text("시연용 설정은 현재 앱에 저장됩니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
            }
        }
    }
}

@Composable
private fun TestNumber(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TestSection(title: String, selected: String, onSelect: (String) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (selected.isEmpty()) {
        FilledTonalButton(onClick = { onSelect(title) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            Text(title, modifier = Modifier.weight(1f), fontSize = 22.sp)
            Text("›", fontSize = 26.sp)
        }
    } else if (selected == title) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
