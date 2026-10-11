package com.lhcomics.app.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lhcomics.app.BuildConfig
import com.lhcomics.app.app
import com.lhcomics.app.ui.common.formatSize
import com.lhcomics.app.ui.viewer.ReaderSettingsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val adsRemoved by app.settings.adsRemoved.collectAsStateWithLifecycle()
    val price by app.billing.price.collectAsStateWithLifecycle()
    val reader by app.settings.reader.collectAsStateWithLifecycle()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var readerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { cacheSize = withContext(Dispatchers.IO) { app.libraries.cacheSize() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("보기")
            ListItem(
                modifier = Modifier.clickable { readerSheet = true },
                headlineContent = { Text("기본 보기 설정") },
                supportingContent = { Text("보기 방식, 넘기는 방향, 배경색, 볼륨 키") },
            )
            HorizontalDivider()

            Section("광고")
            ListItem(
                modifier = Modifier.clickable(enabled = !adsRemoved) {
                    val ok = (context as? Activity)?.let { app.billing.purchase(it) } ?: false
                    if (!ok) Toast.makeText(context, "스토어 정보를 불러오지 못했습니다. 잠시 후 다시 시도하세요.", Toast.LENGTH_SHORT).show()
                },
                headlineContent = { Text(if (adsRemoved) "광고가 제거되었습니다" else "광고 제거") },
                supportingContent = {
                    Text(if (adsRemoved) "구매해 주셔서 감사합니다." else "한 번 구매로 영구 적용 (구독 아님)" + (price?.let { " · $it" } ?: ""))
                },
            )
            Text(
                "읽는 화면에는 광고를 넣지 않습니다. 목록 하단 배너와, 책을 닫을 때 가끔 나오는 전면 광고만 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(Modifier.padding(top = 12.dp))

            Section("저장 공간")
            ListItem(
                modifier = Modifier.clickable {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.libraries.clearCache() }
                        cacheSize = withContext(Dispatchers.IO) { app.libraries.cacheSize() }
                        Toast.makeText(context, "캐시를 비웠습니다", Toast.LENGTH_SHORT).show()
                    }
                },
                headlineContent = { Text("캐시 비우기") },
                supportingContent = {
                    Text("표지 썸네일, 풀어둔 중첩 압축 파일, FTP 임시 파일" + (cacheSize?.let { " · ${formatSize(it)}" } ?: ""))
                },
            )
            HorizontalDivider()

            Section("정보")
            ListItem(headlineContent = { Text("버전") }, supportingContent = { Text(BuildConfig.VERSION_NAME) })
            ListItem(
                headlineContent = { Text("오픈소스 라이선스") },
                supportingContent = {
                    Text("smbj (Apache 2.0), Apache Commons Compress/Net (Apache 2.0), junrar (UnRAR License), OkHttp (Apache 2.0), Coil (Apache 2.0), BouncyCastle (MIT)")
                },
            )
        }
    }

    if (readerSheet) {
        ReaderSettingsSheet(reader, onChange = { app.settings.updateReader(it) }, onDismiss = { readerSheet = false })
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
