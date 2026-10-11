package com.lhcomics.app.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhcomics.app.LhApp
import com.lhcomics.app.data.Progress
import com.lhcomics.core.library.Item
import com.lhcomics.core.library.Location
import com.lhcomics.core.library.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ViewerState {
    data object Loading : ViewerState
    data class Error(val error: Throwable) : ViewerState
    data class Ready(val title: String, val pages: List<Page>, val startPage: Int) : ViewerState
}

@OptIn(FlowPreview::class)
class ViewerViewModel(
    private val app: LhApp,
    val sourceId: String,
    val location: String,
    private val requestedPage: Int,
) : ViewModel() {
    private val loc = Location.parse(location)

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state

    private val _next = MutableStateFlow<Item?>(null)
    val next: StateFlow<Item?> = _next
    private val _previous = MutableStateFlow<Item?>(null)
    val previous: StateFlow<Item?> = _previous

    private val currentPage = MutableStateFlow<Int?>(null)

    init {
        load()
        // 페이지를 빠르게 넘길 때 매번 DB에 쓰지 않도록 묶어서 저장한다.
        viewModelScope.launch {
            currentPage.filterNotNull().debounce(400).collect { save(it) }
        }
    }

    fun load() {
        _state.value = ViewerState.Loading
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                runCatching {
                    val library = app.libraries.library(sourceId)
                    val pages = library.pages(loc)
                    val saved = app.progress.get(sourceId, location)
                    val start = when {
                        requestedPage >= 0 -> requestedPage
                        saved != null && !saved.finished -> saved.page
                        else -> 0
                    }.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    ViewerState.Ready(loc.name.ifEmpty { location }, pages, start)
                }.getOrElse { ViewerState.Error(it) }
            }
            // 앞뒤 권은 페이지를 먼저 띄운 뒤 천천히 찾는다.
            withContext(Dispatchers.IO) {
                runCatching {
                    val library = app.libraries.library(sourceId)
                    _next.value = library.adjacent(loc, 1)
                    _previous.value = library.adjacent(loc, -1)
                }
            }
        }
    }

    fun onPageChanged(page: Int) {
        currentPage.value = page
    }

    private suspend fun save(page: Int) {
        val ready = _state.value as? ViewerState.Ready ?: return
        if (ready.pages.isEmpty()) return
        withContext(Dispatchers.IO) {
            app.progress.save(
                Progress(
                    sourceId = sourceId,
                    location = location,
                    title = ready.title,
                    page = page.coerceIn(0, ready.pages.size - 1),
                    pageCount = ready.pages.size,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    override fun onCleared() {
        // 화면을 닫기 직전 페이지도 남긴다.
        val page = currentPage.value ?: return
        val ready = _state.value as? ViewerState.Ready ?: return
        if (ready.pages.isEmpty()) return
        app.progress.save(
            Progress(sourceId, location, ready.title, page.coerceIn(0, ready.pages.size - 1), ready.pages.size, System.currentTimeMillis())
        )
    }
}
