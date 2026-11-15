package io.github.silleruss.drivy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CompanionSetup(storageStatus: String, onChoose: (Companion) -> Unit) {
    var selected by remember { mutableStateOf(Companion.MOKA) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.widthIn(max = 960.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("처음 함께할 드라이비를 골라주세요", fontSize = 28.sp)
                Text("선택한 친구가 운전 경험과 함께 자라요. 선택 후에는 같은 친구와 계속 동행해요.", fontSize = 20.sp)
                for (companion in Companion.entries) {
                    val isSelected = selected == companion
                    OutlinedButton(onClick = { selected = companion }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
                        .semantics { this.selected = isSelected },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (isSelected)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                        CompanionSprite(false, companion, 0, "${companion.displayName} 미리보기", Modifier.size(88.dp), 4.dp)
                        Column(Modifier.weight(1f).padding(12.dp)) {
                            Text(companion.displayName + if (isSelected) " · 선택됨" else "", fontSize = 24.sp)
                            Text(companion.concept, fontSize = 18.sp)
                        }
                    }
                }
                if (storageStatus == "save_failed") Text("저장하지 못했어요. 친구를 다시 선택해 저장해 주세요.", fontSize = 20.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = { onChoose(selected) }, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
                    Text("${selected.displayName}와 동행 시작", fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
internal fun SpeedPermissionSettings(granted: Boolean, onRequest: () -> Unit) {
    HorizontalDivider()
    Text("운전 습관 측정", fontSize = 22.sp)
    Text("차량 속도로 가감속을 계산하며 원래 속도 값은 저장하지 않아요. 권한 없이도 거리에 따라 성장해요.", fontSize = 18.sp)
    if (granted) {
        Text("차량 속도 권한이 켜져 있어요.", fontSize = 18.sp)
    } else {
        Text("시스템에서 위치 정보로 표시될 수 있어요. 요청 창이 나타나지 않으면 차량 설정에서 드라이비 권한을 확인해 주세요.", fontSize = 18.sp)
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
            Text("차량 속도 권한 요청", fontSize = 20.sp)
        }
    }
}

@Composable
internal fun CompanionDisplaySettings(enabled: Boolean, corner: PetCorner,
    overlayGranted: Boolean, notice: String?, onEnabled: (Boolean) -> Unit,
    onCorner: (PetCorner) -> Unit, onShow: () -> Unit) {
    HorizontalDivider()
    Text("화면 위 드라이비", fontSize = 22.sp)
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("앱 최소화 시 표시", modifier = Modifier.weight(1f), fontSize = 20.sp)
        Switch(checked = enabled, onCheckedChange = onEnabled,
            modifier = Modifier.semantics { contentDescription = "앱 최소화 시 드라이비 화면에 표시" })
    }
    Text(if (overlayGranted) "캐릭터만 표시해요. 드래그로 옮기고, 누르면 앱으로 돌아와요." else
        "켜면 다른 앱 위에 표시 권한을 요청해요.", fontSize = 18.sp)
    Text("표시 위치", fontSize = 20.sp)
    for (choice in PetCorner.entries) {
        OutlinedButton(onClick = { onCorner(choice) }, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
            Text(choice.label + if (choice == corner) " · 선택됨" else "", fontSize = 20.sp)
        }
    }
    Text("주행 중·차량 상태 미확인 시에는 드라이비를 숨겨요. 주차 후 앱에서 다시 켤 수 있어요.", fontSize = 18.sp)
    notice?.let { Text(it, fontSize = 18.sp) }
    Button(onClick = onShow, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
        Text("드라이비 띄우기", fontSize = 20.sp)
    }
}
