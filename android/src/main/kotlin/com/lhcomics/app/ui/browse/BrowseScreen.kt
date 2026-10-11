package com.lhcomics.app.ui.browse

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.lhcomics.app.Routes
import com.lhcomics.app.ads.BannerAd
import com.lhcomics.app.app
import com.lhcomics.app.data.Progress
import com.lhcomics.app.image.CoverImage
import com.lhcomics.app.ui.common.ErrorMessage
import com.lhcomics.app.ui.common.Loading
import com.lhcomics.app.ui.common.formatSize
import com.lhcomics.app.ui.common.friendlyMessage
import com.lhcomics.core.library.Item
import com.lhcomics.core.library.ItemKind
import com.lhcomics.core.library.Location

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(nav: NavController, sourceId: String, location: String) {
    val context = LocalContext.current
    val app = context.app
    val vm: BrowseViewModel = viewModel(key = "$sourceId|$location") { BrowseViewModel(app, sourceId, location) }
    val state by vm.state.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    val grid by app.settings.gridView.collectAsStateWithLifecycle()
    val sourceName = remember(sourceId) { app.sources.get(sourceId)?.name.orEmpty() }
    val loc = remember(location) { Location.parse(location) }

    fun open(item: Item) = vm.resolve(item) { result ->
        result.onSuccess { target ->
            when (target) {
                is OpenTarget.Browse -> nav.navigate(Routes.browse(sourceId, target.location))
                is OpenTarget.Read -> nav.navigate(Routes.viewer(sourceId, target.location, target.page))
            }
        }.onFailure { Toast.makeText(context, friendlyMessage(it), Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(loc.name.ifEmpty { sourceName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            sourceName + location.takeIf { it != "/" }.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    IconButton(onClick = { app.settings.setGridView(!grid) }) {
                        Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "보기 방식")
                    }
                    IconButton(onClick = { nav.popBackStack(Routes.HOME, inclusive = false) }) { Icon(Icons.Default.Home, "홈") }
                },
            )
        },
        floatingActionButton = {
            val loaded = state as? BrowseState.Loaded
            if (loaded != null && (loaded.hasImages || loc.inArchive)) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.viewer(sourceId, location)) },
                    icon = { Icon(Icons.Default.MenuBook, null) },
                    text = { Text("이 폴더 읽기") },
                )
            }
        },
        bottomBar = { BannerAd() },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                BrowseState.Loading -> Loading()
                is BrowseState.Error -> ErrorMessage(s.error, onRetry = vm::refresh)
                is BrowseState.Loaded -> PullToRefreshBox(isRefreshing = false, onRefresh = vm::refresh) {
                    if (s.items.isEmpty()) {
                        Text("이미지나 압축 파일이 없습니다.", Modifier.align(Alignment.Center))
                    } else if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(108.dp),
                            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(s.items, key = { it.location }) { item ->
                                ItemMenuHost(nav, item, s.progress[item.location], vm, ::open) { onLongClick ->
                                    GridCell(sourceId, item, s.progress[item.location], { open(item) }, onLongClick)
                                }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                            items(s.items, key = { it.location }) { item ->
                                ItemMenuHost(nav, item, s.progress[item.location], vm, ::open) { onLongClick ->
                                    ListRow(sourceId, item, s.progress[item.location], { open(item) }, onLongClick)
                                }
                            }
                        }
                    }
                }
            }
            if (opening) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/** 길게 누르면 뜨는 메뉴. */
@Composable
private fun ItemMenuHost(
    nav: NavController,
    item: Item,
    progress: Progress?,
    vm: BrowseViewModel,
    open: (Item) -> Unit,
    content: @Composable (onLongClick: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        content { if (item.kind != ItemKind.IMAGE) menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("열기") }, onClick = { menu = false; open(item) })
            DropdownMenuItem(
                text = { Text("처음부터 읽기") },
                onClick = { menu = false; nav.navigate(Routes.viewer(vm.sourceId, item.location, 0)) },
            )
            if (item.kind == ItemKind.ARCHIVE) {
                DropdownMenuItem(
                    text = { Text("폴더처럼 열기") },
                    onClick = { menu = false; nav.navigate(Routes.browse(vm.sourceId, item.location)) },
                )
            }
            if (progress != null) {
                DropdownMenuItem(text = { Text("읽은 기록 삭제") }, onClick = { menu = false; vm.clearProgress(item) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(sourceId: String, item: Item, progress: Progress?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.padding(4.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.kind.icon(), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            AsyncImage(
                model = CoverImage(sourceId, item.location),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (progress?.finished == true) {
                Icon(
                    Icons.Default.CheckCircle, "다 읽음",
                    tint = Color(0xFF66BB6A),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.White, RoundedCornerShape(50)),
                )
            }
            if (progress != null && !progress.finished) {
                LinearProgressIndicator(
                    progress = { (progress.page + 1f) / progress.pageCount.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(sourceId: String, item: Item, progress: Progress?, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = {
            Box(Modifier.size(40.dp, 56.dp).clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                Icon(item.kind.icon(), null)
                if (item.kind != ItemKind.DIRECTORY) {
                    AsyncImage(CoverImage(sourceId, item.location), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        },
        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = buildList {
                if (item.size > 0) add(formatSize(item.size))
                progress?.let { add(if (it.finished) "다 읽음" else "${it.page + 1}/${it.pageCount}쪽") }
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
        },
    )
}

private fun ItemKind.icon() = when (this) {
    ItemKind.DIRECTORY -> Icons.Default.Folder
    ItemKind.ARCHIVE -> Icons.Default.FolderZip
    ItemKind.IMAGE -> Icons.Default.Image
}
