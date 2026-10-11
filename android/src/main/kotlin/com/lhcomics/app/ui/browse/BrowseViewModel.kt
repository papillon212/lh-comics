package com.lhcomics.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhcomics.app.LhApp
import com.lhcomics.app.data.Progress
import com.lhcomics.core.library.Item
import com.lhcomics.core.library.ItemKind
import com.lhcomics.core.library.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BrowseState {
    data object Loading : BrowseState
    data class Error(val error: Throwable) : BrowseState
    data class Loaded(val items: List<Item>, val progress: Map<String, Progress>) : BrowseState {
        val hasImages: Boolean get() = items.any { it.kind == ItemKind.IMAGE }
    }
}

/** 탭한 항목을 어디로 열지. */
sealed interface OpenTarget {
    data class Browse(val location: String) : OpenTarget
    data class Read(val location: String, val page: Int = -1) : OpenTarget
}

class BrowseViewModel(private val app: LhApp, val sourceId: String, val location: String) : ViewModel() {
    private val _state = MutableStateFlow<BrowseState>(BrowseState.Loading)
    val state: StateFlow<BrowseState> = _state

    private val _opening = MutableStateFlow(false)
    val opening: StateFlow<Boolean> = _opening

    init {
        refresh()
        // 뷰어에서 돌아왔을 때 진행률 배지를 갱신한다.
        viewModelScope.launch {
            app.progress.version.drop(1).collect { reloadProgress() }
        }
    }

    fun refresh() {
        _state.value = BrowseState.Loading
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                runCatching {
                    val items = app.libraries.library(sourceId).list(Location.parse(location))
                    BrowseState.Loaded(items, progressFor(items))
                }.getOrElse { BrowseState.Error(it) }
            }
        }
    }

    private suspend fun reloadProgress() {
        val loaded = _state.value as? BrowseState.Loaded ?: return
        val progress = withContext(Dispatchers.IO) { progressFor(loaded.items) }
        _state.value = loaded.copy(progress = progress)
    }

    private fun progressFor(items: List<Item>) =
        app.progress.under(sourceId, items.filter { it.kind != ItemKind.IMAGE }.map { it.location })

    /**
     * 압축 파일은 안에 다른 압축 파일이 있으면 폴더처럼 열고, 아니면 바로 뷰어로 연다.
     * 이미지를 탭하면 현재 위치를 한 권으로 보고 그 페이지부터 연다.
     */
    fun resolve(item: Item, onResult: (Result<OpenTarget>) -> Unit) {
        when (item.kind) {
            ItemKind.DIRECTORY -> onResult(Result.success(OpenTarget.Browse(item.location)))
            ItemKind.IMAGE -> background(onResult) {
                val pages = app.libraries.library(sourceId).pages(Location.parse(location))
                OpenTarget.Read(location, pages.indexOfFirst { it.location == item.location }.coerceAtLeast(0))
            }
            ItemKind.ARCHIVE -> background(onResult) {
                if (app.libraries.library(sourceId).containsArchives(Location.parse(item.location))) OpenTarget.Browse(item.location)
                else OpenTarget.Read(item.location)
            }
        }
    }

    private fun background(onResult: (Result<OpenTarget>) -> Unit, block: () -> OpenTarget) {
        if (_opening.value) return
        _opening.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            _opening.value = false
            onResult(result)
        }
    }

    fun clearProgress(item: Item) {
        viewModelScope.launch(Dispatchers.IO) { app.progress.delete(sourceId, item.location) }
    }
}
