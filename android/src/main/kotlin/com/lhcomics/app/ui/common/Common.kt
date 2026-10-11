package com.lhcomics.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lhcomics.core.archive.UnsupportedArchiveException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorMessage(error: Throwable, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(friendlyMessage(error), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (onRetry != null) Button(onClick = onRetry) { Text("다시 시도") }
    }
}

/** 기술적인 예외를 사용자에게 보여줄 문장으로 바꾼다. */
fun friendlyMessage(e: Throwable): String {
    val root = generateSequence(e) { it.cause }.last()
    return when {
        e is UnsupportedArchiveException -> e.message ?: "지원하지 않는 압축 형식입니다"
        root is UnknownHostException -> "서버 주소를 찾을 수 없습니다. 주소와 네트워크 연결을 확인하세요."
        root is ConnectException -> "서버에 연결할 수 없습니다. 같은 네트워크에 있는지, 포트가 맞는지 확인하세요."
        root is SocketTimeoutException -> "서버 응답이 없습니다(시간 초과)."
        e is FileNotFoundException || root is FileNotFoundException -> "파일이나 폴더를 찾을 수 없습니다."
        (e.message ?: "").contains("STATUS_LOGON_FAILURE") -> "아이디 또는 비밀번호가 틀렸습니다."
        (e.message ?: "").contains("STATUS_BAD_NETWORK_NAME") -> "공유 폴더 이름이 틀렸습니다."
        (e.message ?: "").contains("STATUS_ACCESS_DENIED") -> "접근 권한이 없습니다."
        else -> "오류가 발생했습니다: ${root.message ?: root.javaClass.simpleName}"
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
