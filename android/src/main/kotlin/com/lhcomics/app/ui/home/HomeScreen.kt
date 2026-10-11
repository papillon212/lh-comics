package com.lhcomics.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.lhcomics.app.Routes
import com.lhcomics.app.ads.BannerAd
import com.lhcomics.app.app
import com.lhcomics.app.data.Progress
import com.lhcomics.app.image.CoverImage
import com.lhcomics.core.source.SourceConfig
import com.lhcomics.core.source.SourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val app = LocalContext.current.app
    val sources by app.sources.sources.collectAsStateWithLifecycle()
    val recent by app.progress.recent.collectAsStateWithLifecycle()
    val validRecent = recent.filter { p -> sources.any { it.id == p.sourceId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LH Comics") },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, "설정") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.editSource()) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("저장소 추가") },
            )
        },
        bottomBar = { BannerAd() },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (validRecent.isNotEmpty()) {
                item { SectionTitle("최근 읽은 책") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(validRecent, key = { it.sourceId + it.location }) { p ->
                            RecentCard(p) { nav.navigate(Routes.viewer(p.sourceId, p.location, p.page)) }
                        }
                    }
                }
            }
            item { SectionTitle("저장소") }
            if (sources.isEmpty()) {
                item {
                    Text(
                        "NAS(SMB), FTP, WebDAV 또는 기기 폴더를 추가하세요.\n압축 파일 안의 압축 파일까지 바로 볼 수 있습니다.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sources, key = { it.id }) { source ->
                ListItem(
                    modifier = Modifier.clickable { nav.navigate(Routes.browse(source.id, "/")) },
                    leadingContent = { Icon(source.icon(), null) },
                    headlineContent = { Text(source.name) },
                    supportingContent = { Text(source.summary(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { nav.navigate(Routes.editSource(source.id)) }) { Icon(Icons.Default.Edit, "편집") }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun RecentCard(p: Progress, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = CoverImage(p.sourceId, p.location),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (p.pageCount > 0) (p.page + 1f) / p.pageCount else 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
        Text(p.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${p.page + 1} / ${p.pageCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SourceConfig.icon() = when (type) {
    SourceType.LOCAL -> Icons.Default.Folder
    SourceType.SMB -> Icons.Default.Lan
    SourceType.FTP -> Icons.Default.Storage
    SourceType.WEBDAV -> Icons.Default.Cloud
}

private fun SourceConfig.summary(): String = when (type) {
    SourceType.LOCAL -> "기기 폴더"
    SourceType.SMB -> "smb://$host/$share$basePath"
    SourceType.FTP -> (if (secure) "ftps://" else "ftp://") + host + (if (port > 0) ":$port" else "") + basePath
    SourceType.WEBDAV -> (if (secure) "https://" else "http://") + host + (if (port > 0) ":$port" else "") + basePath
}
