package io.github.silleruss.drivy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DriveReportsPanel(journal: DriveJournal, status: String, companion: Companion,
    onEnabled: (Boolean) -> Unit, onGoal: (DriveGoal) -> Unit, onRecord: (DriveRecord) -> Unit,
    onDelete: () -> Unit, onRetry: () -> Unit, onEnvironment: (DriveEnvironment) -> Unit = {}) {
    var opened by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val latest = journal.records.lastOrNull()
    val unavailable = status in listOf("corrupt", "deleting", "delete_failed")
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("측정 구간 리포트", fontSize = 26.sp)
            Text(if (latest == null) "아직 측정 기록이 없어요." else
                "${reportDate(latest.startedAt)} · ${if (latest.hasDistance) km(latest.distanceKm) + " km" else "거리 미확인"} · ${if (latest.hasGap) "수집 공백 있음" else "기록 상세 보기"}",
                fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            JournalStatus(status, onRetry)
            if (latest != null) ReportButton("최근 기록 상세 보기") { selectedId = latest.id; opened = true }
            ReportButton("기록·목표·설정") { selectedId = null; opened = true }
        }
    }
    if (opened) {
        val selected = journal.records.find { it.id == selectedId }
        Dialog(onDismissRequest = { opened = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 1100.dp).fillMaxWidth(0.94f).fillMaxHeight(0.9f)) {
              BoxWithConstraints {
                val wide = maxWidth / LocalDensity.current.fontScale >= 800.dp
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (selected == null) "기록·목표·설정" else "측정 기록 상세", fontSize = 24.sp)
                    val scroll = rememberScrollState()
                    LaunchedEffect(selectedId) { scroll.scrollTo(0) }
                    Text(if (scroll.value < scroll.maxValue) "아래로 스크롤하면 더 볼 수 있어요 ↓" else if (scroll.maxValue > 0) "위로 스크롤하면 이전 내용을 볼 수 있어요 ↑" else "모든 내용이 표시돼요", fontSize = 16.sp)
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (wide && selected != null) {
                        Column(Modifier.weight(0.36f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("기록 목록", fontSize = 22.sp)
                            for (record in journal.records.asReversed()) {
                                FilterChip(selected = record.id == selected.id, onClick = { selectedId = record.id },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                    label = { Text("${reportDate(record.startedAt)} · ${if (record.hasDistance) km(record.distanceKm) + " km" else "거리 미확인"}", fontSize = 18.sp) })
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    JournalStatus(status, onRetry)
                    if (selected == null) {
                        Text("앱 화면이 열린 동안 수신한 정보만 기록해요. 앱이 중단되거나 연결이 끊긴 뒤에는 새 기록으로 저장해요. 전체 주행을 복원할 수는 없어요.", fontSize = 20.sp)
                        Text("동의하시면 거리, 속도를 측정한 시간, 가감속 집계와 직접 입력한 환경, 상황, 목표, 회고를 이 기기에 저장해요. GPS와 원래 속도 기록은 사용하지 않으며 서버에 전송하지 않아요.", fontSize = 18.sp)
                        Text("최근 30일, 최대 1,000개 기록을 보관해요. 기록 중단은 기존 기록을 유지하고, 전체 삭제는 입력·목표·동의도 지워요. 펫 성장은 유지해요.", fontSize = 18.sp)
                        ReportButton(if (journal.enabled) "기록·분석 동의 중단" else "기록·분석에 동의하고 시작", !unavailable) { onEnabled(!journal.enabled) }
                        Text("다음 기록의 목표: ${journal.goal.label}", fontSize = 22.sp)
                        Text("실제 차량에서 기준을 검증하기 전까지 운전 습관이나 개선 여부를 자동으로 판단하지 않아요. 목표는 직접 선택해 주세요.", fontSize = 18.sp)
                        ReportChoices(DriveGoal.entries, journal.goal, { it.label }, !unavailable, onGoal)
                        Text("다음 기록의 평소 환경: ${journal.environment.label}", fontSize = 22.sp)
                        Text("선택은 건너뛸 수 있어요. 사용자 입력이며 실제 도로·정체·날씨를 확인한 정보는 아니에요.", fontSize = 18.sp)
                        ReportChoices(DriveEnvironment.entries, journal.environment, { it.label }, !unavailable, onEnvironment)
                        val week = journal.week(System.currentTimeMillis())
                        Text("최근 7일 관측 요약", fontSize = 22.sp)
                        Text("${week.size}개 일부 기록 · 거리 확인 ${week.count { it.hasDistance }}개 · ${km(week.sumOf { it.distanceKm })} km · 유효 측정 ${duration(week.sumOf { it.coveredMs })}", fontSize = 20.sp)
                        Text("합계는 관측한 범위이며 한 주 전체 주행이나 개인 습관 평가가 아니에요. 거리와 속도의 관측 범위도 다를 수 있어요.", fontSize = 18.sp)
                        if (journal.records.isEmpty()) Text("아직 측정 기록이 없어요.", fontSize = 20.sp)
                        for (record in journal.records.asReversed()) ReportButton("${reportDate(record.startedAt)} · ${if (record.hasDistance) km(record.distanceKm) + " km" else "거리 미확인"}") { selectedId = record.id }
                        ReportButton("기록·입력·목표 전체 삭제", status != "deleting") { deleting = true }
                    } else {
                        Text("${companion.displayName} · ${reportFeedback(selected)}", fontSize = 20.sp)
                        ReportFacts(selected)
                        Text("가감속 이벤트 판정은 실차 보정 전이므로 횟수·습관·안전 점수를 제공하지 않아요.", fontSize = 18.sp)
                        Text("${companion.displayName} · 어떤 상황이었는지 알려주시면 기록에 남겨요.", fontSize = 20.sp)
                        Text("사용자 입력: ${selected.driver.label} / ${selected.context.label}", fontSize = 20.sp)
                        Text("사용자가 알려준 환경: ${selected.environment.label}", fontSize = 20.sp)
                        ReportChoices(DriveEnvironment.entries, selected.environment, { it.label }, !unavailable) { onRecord(selected.copy(environment = it)) }
                        Text("운전자", fontSize = 20.sp)
                        ReportChoices(DriveDriver.entries, selected.driver, { it.label }, !unavailable) { onRecord(selected.copy(driver = it)) }
                        Text("주행 상황", fontSize = 20.sp)
                        ReportChoices(DriveContext.entries, selected.context, { it.label }, !unavailable) { onRecord(selected.copy(context = it)) }
                        Text(if (selected.includedInPersonalAnalysis) "내 기록으로 선택했어요. 자동 습관 분석은 보류 중이에요." else
                            "개인 경향 분석에서 제외해요. 관측 수치는 바꾸지 않아요.", fontSize = 18.sp)
                        Text("기록 시작 시 선택한 목표: ${selected.goal.label}", fontSize = 20.sp)
                        if (selected.goal != DriveGoal.NONE) {
                            Text("${companion.displayName} · 선택한 목표에 대해 느낀 점을 남겨주세요.", fontSize = 20.sp)
                            Text("사용자 회고: ${selected.reflection.label}", fontSize = 20.sp)
                            ReportChoices(DriveReflection.entries, selected.reflection, { it.label }, !unavailable) { onRecord(selected.copy(reflection = it)) }
                        }
                        ReportButton("기록 목록으로") { selectedId = null }
                    }
                    }
                    }
                    ReportButton("닫기") { opened = false }
                }
              }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("주행 기록을 모두 삭제할까요?") },
        text = { Text("기록·상황 입력·목표·회고와 기록 동의를 삭제해요. 펫과 경험치는 유지해요.", fontSize = 20.sp) },
        confirmButton = { TextButton(onClick = { deleting = false; selectedId = null; onDelete() }) { Text("전체 삭제 확인") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("취소") } })
}

internal fun reportFeedback(record: DriveRecord?): String = when {
    record == null -> "동의하시면 측정 기록을 확인할 수 있어요."
    record.hasGap -> "수집 공백이 있어 확인한 구간만 보여드려요."
    else -> "앱에서 확인한 일부 구간이에요. 이 기록만으로 운전 습관을 판단하지 않아요."
}

@Composable
private fun ReportFacts(record: DriveRecord) {
    Text("${reportDate(record.startedAt)} – ${reportDate(record.endedAt)}", fontSize = 18.sp)
    Text("관측 거리 ${if (record.hasDistance) km(record.distanceKm) + " km" else "미확인"} · 유효 속도 측정 ${if (record.coveredMs > 0) duration(record.coveredMs) else "없음"}", fontSize = 20.sp)
    Text("일부 기록 · ${if (record.hasGap) "수집 공백 있음" else "전체 주행 수집 여부 미확인"} · ${when (record.endReason) {
        "collecting" -> "측정 중"
        "stopped" -> "앱 화면 종료"
        "disconnected" -> "연결 중단"
        "paused" -> "기록 구간 종료"
        else -> "앱 중단 후 복원"
    }}", fontSize = 18.sp)
    Text("거리는 차량 누적 거리에서 확인된 증가량이에요. 시간은 속도를 연속으로 측정한 구간의 합계예요. 거리와 시간의 측정 구간은 다를 수 있어요.", fontSize = 18.sp)
}

@Composable
private fun JournalStatus(status: String, onRetry: () -> Unit) {
    val message = when (status) {
        "corrupt" -> "리포트를 복원하지 못해 수집을 중지했어요. 원본을 보존하고 있어요. 전체 삭제 후 다시 시작할 수 있어요."
        "save_failed" -> "리포트 저장 실패 · 화면의 기록은 아직 저장되지 않았을 수 있어요."
        "delete_failed" -> "삭제 실패 · 기록이 기기에 남아 있을 수 있어요. 수집을 중지했으니 전체 삭제를 다시 시도해 주세요."
        "deleting" -> "기록을 삭제하고 있어요."
        else -> null
    }
    if (message != null) Text(message, fontSize = 18.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (status == "save_failed") ReportButton("리포트 저장 재시도", onClick = onRetry)
}

@Composable
private fun ReportButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.widthIn(max = 380.dp).heightIn(min = 76.dp)) { Text(label, fontSize = 20.sp) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ReportChoices(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 56.dp), label = { Text(label(option), fontSize = 18.sp) })
        }
    }
}

private fun reportDate(ms: Long) = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(ms))
private fun km(value: Double) = String.format(Locale.ROOT, "%.2f", value)
private fun duration(ms: Long) = "${ms / 60_000}분 ${ms / 1000 % 60}초"
