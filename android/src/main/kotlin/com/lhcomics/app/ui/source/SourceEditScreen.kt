package com.lhcomics.app.ui.source

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lhcomics.app.app
import com.lhcomics.app.ui.common.friendlyMessage
import com.lhcomics.core.source.SourceConfig
import com.lhcomics.core.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceEditScreen(nav: NavController, sourceId: String?) {
    val context = LocalContext.current
    val app = context.app
    val existing = remember(sourceId) { sourceId?.let { app.sources.get(it) } }
    val scope = rememberCoroutineScope()

    var type by rememberSaveable { mutableStateOf(existing?.type ?: SourceType.SMB) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var share by rememberSaveable { mutableStateOf(existing?.share.orEmpty()) }
    var basePath by rememberSaveable { mutableStateOf(existing?.basePath.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(existing?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(existing?.password.orEmpty()) }
    var domain by rememberSaveable { mutableStateOf(existing?.domain.orEmpty()) }
    var secure by rememberSaveable { mutableStateOf(existing?.secure ?: false) }
    var legacyEncoding by rememberSaveable { mutableStateOf(existing?.encoding == "CP949") }

    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            host = uri.toString()
            if (name.isBlank()) name = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast(':').ifEmpty { "기기 폴더" }
        }
    }

    fun build() = SourceConfig(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name.trim().ifEmpty { if (type == SourceType.LOCAL) "기기 폴더" else host.trim() },
        type = type,
        host = host.trim(),
        port = port.toIntOrNull() ?: 0,
        share = share.trim().trim('/', '\\'),
        basePath = basePath.trim(),
        username = username.trim(),
        password = password,
        domain = domain.trim(),
        secure = secure,
        encoding = if (legacyEncoding) "CP949" else "",
    )

    val valid = host.isNotBlank() && (type != SourceType.SMB || share.isNotBlank())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "저장소 추가" else "저장소 편집") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "삭제") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SourceType.SMB to "SMB/NAS", SourceType.FTP to "FTP", SourceType.WEBDAV to "WebDAV", SourceType.LOCAL to "기기")
                    .forEach { (t, label) ->
                        FilterChip(selected = type == t, onClick = { type = t; status = null }, label = { Text(label) })
                    }
            }

            OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            if (type == SourceType.LOCAL) {
                OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (host.isEmpty()) "폴더 선택" else "선택됨: ${Uri.decode(host).substringAfterLast(':')}")
                }
                Text(
                    "내장 저장소, SD카드, USB 폴더를 고를 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    host, { host = it },
                    label = { Text("서버 주소") },
                    placeholder = { Text("192.168.0.10 또는 nas.local") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) },
                    label = { Text("포트 (비우면 기본값)") },
                    placeholder = { Text(defaultPort(type, secure)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type == SourceType.SMB) {
                    OutlinedTextField(
                        share, { share = it },
                        label = { Text("공유 폴더 이름") },
                        placeholder = { Text("예: comics, video") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    basePath, { basePath = it },
                    label = { Text("시작 경로 (선택)") },
                    placeholder = { Text("/만화") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(username, { username = it }, label = { Text("아이디 (비우면 게스트)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("비밀번호") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type == SourceType.SMB) {
                    OutlinedTextField(domain, { domain = it }, label = { Text("도메인 (보통 비움)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (type != SourceType.SMB) {
                    SwitchRow(if (type == SourceType.FTP) "FTPS (암호화)" else "HTTPS", secure) { secure = it }
                }
                SwitchRow("한글 파일명이 깨질 때 (CP949)", legacyEncoding) { legacyEncoding = it }
            }

            status?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = valid && !testing,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        testing = true
                        status = "연결 확인 중…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { app.libraries.test(build()) } }
                            status = result.fold({ "연결 성공" }, { friendlyMessage(it) })
                            testing = false
                        }
                    },
                ) { Text("연결 테스트") }
                Button(
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val config = build()
                        app.sources.save(config)
                        app.libraries.evict(config.id)
                        nav.popBackStack()
                    },
                ) { Text("저장") }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("저장소 삭제") },
            text = { Text("'${existing.name}'을(를) 삭제할까요? 읽은 기록도 함께 지워집니다. 원본 파일은 지워지지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    app.libraries.evict(existing.id)
                    app.sources.delete(existing.id)
                    app.progress.deleteSource(existing.id)
                    nav.popBackStack()
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

private fun defaultPort(type: SourceType, secure: Boolean) = when (type) {
    SourceType.SMB -> "445"
    SourceType.FTP -> "21"
    SourceType.WEBDAV -> if (secure) "443" else "80"
    SourceType.LOCAL -> ""
}
