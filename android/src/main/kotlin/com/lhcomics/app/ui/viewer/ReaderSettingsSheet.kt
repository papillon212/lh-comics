package com.lhcomics.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lhcomics.app.data.FitMode
import com.lhcomics.app.data.ReaderLayout
import com.lhcomics.app.data.ReaderSettings
import com.lhcomics.app.data.ReadingDirection

private val backgrounds = listOf(0xFF000000, 0xFF202020, 0xFF808080, 0xFFF5F0E6, 0xFFFFFFFF)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label("보기 방식")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("한 장씩", settings.layout == ReaderLayout.PAGED) { onChange { it.copy(layout = ReaderLayout.PAGED) } }
                Chip("세로 스크롤 (웹툰)", settings.layout == ReaderLayout.VERTICAL) { onChange { it.copy(layout = ReaderLayout.VERTICAL) } }
                Chip("가로 스크롤", settings.layout == ReaderLayout.HORIZONTAL) { onChange { it.copy(layout = ReaderLayout.HORIZONTAL) } }
            }

            if (settings.layout != ReaderLayout.VERTICAL) {
                Label("넘기는 방향")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("왼쪽 → 오른쪽", settings.direction == ReadingDirection.LTR) { onChange { it.copy(direction = ReadingDirection.LTR) } }
                    Chip("오른쪽 → 왼쪽 (일본 만화)", settings.direction == ReadingDirection.RTL) { onChange { it.copy(direction = ReadingDirection.RTL) } }
                }
            }

            if (settings.layout == ReaderLayout.PAGED) {
                Label("맞춤")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("화면에 맞춤", settings.fit == FitMode.SCREEN) { onChange { it.copy(fit = FitMode.SCREEN) } }
                    Chip("너비에 맞춤", settings.fit == FitMode.WIDTH) { onChange { it.copy(fit = FitMode.WIDTH) } }
                }
            } else {
                Label("페이지 간격 ${settings.pageGap}dp")
                Slider(
                    value = settings.pageGap.toFloat(),
                    onValueChange = { v -> onChange { it.copy(pageGap = v.toInt()) } },
                    valueRange = 0f..32f,
                    steps = 7,
                )
            }

            Label("배경색")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                backgrounds.forEach { color ->
                    Box(
                        Modifier.size(36.dp).background(Color(color), CircleShape)
                            .border(
                                if (settings.background == color) 3.dp else 1.dp,
                                if (settings.background == color) MaterialTheme.colorScheme.primary else Color.Gray,
                                CircleShape,
                            )
                            .clickable { onChange { it.copy(background = color) } },
                    )
                }
            }

            Toggle("화면 꺼짐 방지", settings.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }
            Toggle("볼륨 키로 넘기기", settings.volumeKeys) { v -> onChange { it.copy(volumeKeys = v) } }
            Toggle("화면 가장자리 탭으로 넘기기", settings.tapZones) { v -> onChange { it.copy(tapZones = v) } }
            Toggle("페이지 번호 표시", settings.showPageNumber) { v -> onChange { it.copy(showPageNumber = v) } }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
