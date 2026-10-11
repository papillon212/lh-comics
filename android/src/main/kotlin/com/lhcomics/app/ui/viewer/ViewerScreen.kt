package com.lhcomics.app.ui.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.lhcomics.app.MainActivity
import com.lhcomics.app.Routes
import com.lhcomics.app.app
import com.lhcomics.app.data.FitMode
import com.lhcomics.app.data.ReaderLayout
import com.lhcomics.app.data.ReaderSettings
import com.lhcomics.app.data.ReadingDirection
import com.lhcomics.app.image.PageImage
import com.lhcomics.app.ui.common.ErrorMessage
import com.lhcomics.core.library.Item
import com.lhcomics.core.library.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 화면 이동 명령. 레이아웃마다 다르게 구현한다. */
private interface ReaderController {
    val current: Int
    fun step(forward: Boolean)
    fun jump(page: Int)
}

@Composable
fun ViewerScreen(nav: NavController, sourceId: String, location: String, page: Int) {
    val context = LocalContext.current
    val app = context.app
    val vm: ViewerViewModel = viewModel(key = "$sourceId|$location") { ViewerViewModel(app, sourceId, location, page) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by app.settings.reader.collectAsStateWithLifecycle()

    KeepScreenOn(settings.keepScreenOn)
    DisposableEffect(Unit) {
        onDispose { (context as? Activity)?.let { app.ads.onBookClosed(it) } }
    }

    Box(Modifier.fillMaxSize().background(Color(settings.background))) {
        when (val s = state) {
            ViewerState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is ViewerState.Error -> Surface { ErrorMessage(s.error, onRetry = vm::load) }
            is ViewerState.Ready -> if (s.pages.isEmpty()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("이 위치에는 바로 볼 이미지가 없습니다.")
                        Button(onClick = {
                            nav.navigate(Routes.browse(sourceId, location)) { popUpTo(nav.currentBackStackEntry!!.destination.id) { inclusive = true } }
                        }) { Text("폴더로 열기") }
                    }
                }
            } else {
                Reader(nav, vm, s, settings)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Reader(nav: NavController, vm: ViewerViewModel, state: ViewerState.Ready, settings: ReaderSettings) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val next by vm.next.collectAsStateWithLifecycle()
    val previous by vm.previous.collectAsStateWithLifecycle()
    var menuVisible by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val pages = state.pages
    // 마지막 항목은 "다음 권" 카드다.
    val itemCount = pages.size + 1

    Immersive(!menuVisible && !settingsOpen)

    val pagerState = rememberPagerState(initialPage = state.startPage) { itemCount }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.startPage)
    val controller = remember(settings.layout, pagerState, listState) {
        controllerFor(settings.layout, pagerState, listState, scope, itemCount)
    }

    // 현재 페이지 추적 → 진행률 저장 + 다음 페이지 미리 받기
    val loader = remember { SingletonImageLoader.get(context) }
    LaunchedEffect(controller) {
        snapshotFlow { controller.current }.collect { index ->
            vm.onPageChanged(index.coerceAtMost(pages.size - 1))
            for (i in index + 1..minOf(index + 3, pages.size - 1)) {
                loader.enqueue(ImageRequest.Builder(context).data(PageImage(vm.sourceId, pages[i].location)).build())
            }
        }
    }

    fun openBook(item: Item) {
        nav.navigate(Routes.viewer(vm.sourceId, item.location, 0)) {
            popUpTo(nav.currentBackStackEntry!!.destination.id) { inclusive = true }
        }
    }

    // 끝 카드에서 한 번 더 넘기면 다음 권으로 간다.
    fun step(forward: Boolean) {
        if (forward && controller.current >= pages.size) next?.let(::openBook) ?: run { menuVisible = true }
        else controller.step(forward)
    }

    val currentStep by rememberUpdatedState(::step)
    val activity = context as? MainActivity
    DisposableEffect(settings.volumeKeys, activity) {
        activity?.volumeKeyHandler = if (settings.volumeKeys) { up -> currentStep(!up); true } else null
        onDispose { activity?.volumeKeyHandler = null }
    }

    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    val rtl = settings.direction == ReadingDirection.RTL
    val onTapZone: (Float, Float) -> Unit = { fx, fy ->
        val zone = when {
            !settings.tapZones -> 0
            settings.layout == ReaderLayout.VERTICAL -> if (fy < 0.3f) -1 else if (fy > 0.7f) 1 else 0
            fx < 0.3f -> if (rtl) 1 else -1
            fx > 0.7f -> if (rtl) -1 else 1
            else -> 0
        }
        if (zone == 0 || menuVisible) menuVisible = !menuVisible else step(zone > 0)
    }

    val endCard: @Composable () -> Unit = {
        EndCard(next, onNext = { next?.let(::openBook) }, onClose = { nav.popBackStack() })
    }

    when (settings.layout) {
        ReaderLayout.PAGED -> PagedReader(vm.sourceId, pages, pagerState, settings, zoomed, { zoomed = it }, onTapZone, endCard)
        ReaderLayout.VERTICAL -> ContinuousReader(vm.sourceId, pages, listState, settings, vertical = true, onTapZone, endCard)
        ReaderLayout.HORIZONTAL -> ContinuousReader(vm.sourceId, pages, listState, settings, vertical = false, onTapZone, endCard)
    }

    if (settings.showPageNumber && !menuVisible) {
        Box(Modifier.fillMaxSize().navigationBarsPadding()) {
            Text(
                "${(controller.current + 1).coerceAtMost(pages.size)} / ${pages.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color(0x88000000), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }

    AnimatedVisibility(menuVisible, enter = fadeIn(), exit = fadeOut()) {
        ReaderMenu(
            title = state.title,
            current = controller.current.coerceAtMost(pages.size - 1),
            count = pages.size,
            rtl = rtl && settings.layout != ReaderLayout.VERTICAL,
            hasPrevious = previous != null,
            hasNext = next != null,
            onBack = { nav.popBackStack() },
            onSeek = controller::jump,
            onPreviousBook = { previous?.let(::openBook) },
            onNextBook = { next?.let(::openBook) },
            onSettings = { settingsOpen = true },
        )
    }

    if (settingsOpen) {
        ReaderSettingsSheet(settings, onChange = { app.settings.updateReader(it) }, onDismiss = { settingsOpen = false })
    }
}

private fun controllerFor(
    layout: ReaderLayout,
    pager: PagerState,
    list: LazyListState,
    scope: CoroutineScope,
    itemCount: Int,
): ReaderController = when (layout) {
    ReaderLayout.PAGED -> object : ReaderController {
        override val current get() = pager.currentPage
        override fun step(forward: Boolean) {
            val target = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, itemCount - 1)
            scope.launch { pager.animateScrollToPage(target) }
        }
        override fun jump(page: Int) {
            scope.launch { pager.scrollToPage(page) }
        }
    }
    else -> object : ReaderController {
        override val current get() = list.firstVisibleItemIndex
        override fun step(forward: Boolean) {
            val viewport = list.layoutInfo.let {
                if (layout == ReaderLayout.VERTICAL) it.viewportSize.height else it.viewportSize.width
            }
            // 화면의 85%씩 넘겨서 앞 화면 끝부분이 살짝 남게 한다.
            scope.launch { list.animateScrollBy(viewport * 0.85f * if (forward) 1 else -1) }
        }
        override fun jump(page: Int) {
            scope.launch { list.scrollToItem(page) }
        }
    }
}

@Composable
private fun PagedReader(
    sourceId: String,
    pages: List<Page>,
    pagerState: PagerState,
    settings: ReaderSettings,
    zoomed: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTapZone: (Float, Float) -> Unit,
    endCard: @Composable () -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        reverseLayout = settings.direction == ReadingDirection.RTL,
        beyondViewportPageCount = 1,
        userScrollEnabled = !zoomed,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        if (index >= pages.size) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { onTapZone(it.x / size.width, it.y / size.height) }
            }) { endCard() }
            return@HorizontalPager
        }
        ZoomablePage(
            resetKey = pagerState.currentPage,
            onZoomChanged = onZoomChanged,
            onTap = onTapZone,
        ) {
            val model = PageImage(sourceId, pages[index].location)
            if (settings.fit == FitMode.WIDTH) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AsyncImage(model, null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            } else {
                AsyncImage(model, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ContinuousReader(
    sourceId: String,
    pages: List<Page>,
    listState: LazyListState,
    settings: ReaderSettings,
    vertical: Boolean,
    onTapZone: (Float, Float) -> Unit,
    endCard: @Composable () -> Unit,
) {
    // 이미지를 받기 전에는 높이를 모르므로 받은 뒤의 비율을 기억해 스크롤 위치가 튀지 않게 한다.
    val ratios = remember { mutableStateMapOf<Int, Float>() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val gap = settings.pageGap.dp

    Box(
        Modifier.fillMaxSize()
            .pinchZoom(
                vertical = vertical,
                scale = { scale },
                offset = { offset },
                onTransform = { s, o ->
                    scale = s
                    offset = o
                },
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { scale = 1f; offset = 0f },
                    onTap = { onTapZone(it.x / size.width, it.y / size.height) },
                )
            }
            .zoomLayer({ scale }, { offset }, vertical),
    ) {
        val item: @Composable (Int, Page) -> Unit = { index, page ->
            val ratio = ratios[index] ?: 0.7f
            AsyncImage(
                model = PageImage(sourceId, page.location),
                contentDescription = null,
                contentScale = if (vertical) ContentScale.FillWidth else ContentScale.FillHeight,
                onSuccess = { result ->
                    val img = result.result.image
                    if (img.width > 0 && img.height > 0) ratios[index] = img.width.toFloat() / img.height
                },
                modifier = if (vertical) Modifier.fillMaxWidth().aspectRatio(ratio)
                else Modifier.fillMaxHeight().aspectRatio(ratio),
            )
        }
        if (vertical) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(pages, key = { i, _ -> i }) { i, p -> item(i, p) }
                item { Box(Modifier.fillMaxWidth().height(320.dp)) { endCard() } }
            }
        } else {
            val reverse = settings.direction == ReadingDirection.RTL
            LazyRow(
                state = listState,
                reverseLayout = reverse,
                horizontalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(pages, key = { i, _ -> i }) { i, p -> item(i, p) }
                item { Box(Modifier.fillMaxHeight().width(320.dp)) { endCard() } }
            }
        }
    }
}

@Composable
private fun EndCard(next: Item?, onNext: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("마지막 페이지입니다", color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (next != null) {
            Text(next.name, color = Color.LightGray, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Button(onClick = onNext) { Text("다음 권 읽기") }
        }
        OutlinedButton(onClick = onClose) { Text("닫기", color = Color.White) }
    }
}

@Composable
private fun ReaderMenu(
    title: String,
    current: Int,
    count: Int,
    rtl: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onSeek: (Int) -> Unit,
    onPreviousBook: () -> Unit,
    onNextBook: () -> Unit,
    onSettings: () -> Unit,
) {
    val barColor = Color(0xE6202020)
    var seek by remember(current) { mutableFloatStateOf(current.toFloat()) }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(barColor).statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White) }
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Default.Tune, "보기 설정", tint = Color.White) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(barColor).navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                "${seek.toInt() + 1} / $count",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            // 오른쪽→왼쪽으로 읽을 때는 슬라이더 방향도 뒤집는다.
            val direction = if (rtl) LayoutDirection.Rtl else LocalLayoutDirection.current
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPreviousBook, enabled = hasPrevious) {
                        Icon(Icons.Default.SkipPrevious, "이전 권", tint = if (hasPrevious) Color.White else Color.Gray)
                    }
                    Slider(
                        value = seek,
                        onValueChange = { seek = it },
                        onValueChangeFinished = { onSeek(seek.toInt()) },
                        valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onNextBook, enabled = hasNext) {
                        Icon(Icons.Default.SkipNext, "다음 권", tint = if (hasNext) Color.White else Color.Gray)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** 읽는 동안 상태바/내비게이션바를 숨긴다. */
@Composable
private fun Immersive(hidden: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    DisposableEffect(hidden) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
