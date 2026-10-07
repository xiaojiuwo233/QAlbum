package com.xjw.qalbum.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.os.Build
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.db.entity.OcrTextEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.QuarantineManager
import com.xjw.qalbum.service.cleanup.PictureDeleteOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 图片详情页 ViewModel
 *
 * 时间线不再全量装载 id（200 万行时查询秒级、装箱 List 常驻数十 MB），改为以当前图为锚点的
 * keyset **窗口**（[ImageRepository.timelineWindowAround]，前后各 [ImageRepository.TIMELINE_WINDOW_RADIUS]）：
 * 滑近窗口任一端时用端点图为新锚点续页；收藏模式的 id 集由用户手动收藏产生、规模小，仍全量装载。
 * 当前图的完整实体按 index 用 [ImageRepository.getById] 按需加载。
 */
class ImageDetailViewModel(
    private val imageRepository: ImageRepository,
    private val imageFileManager: ImageFileManager,
    private val quarantineManager: QuarantineManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ImageDetailState>(ImageDetailState.Loading)
    val state: StateFlow<ImageDetailState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /**
     * 不可逆操作（保存并删除 / 移入回收站 / 永久删除）的 UI 防重入闸（C004）：
     * 进行中忽略再次触发。QuarantineManager 的 inFlight 已保证双击第二次安全返回 false
     * （不会销毁文件），此闸避免的是双 toast / 双次跳页等 UX 层混乱。
     */
    private val _operationBusy = MutableStateFlow(false)
    val operationBusy: StateFlow<Boolean> = _operationBusy.asStateFlow()
    private var favoritesOnly = false
    private var sort: GallerySort = GallerySort.DATE_DESC

    /** 已按该参数加载过（配置变更后 Screen 的 LaunchedEffect 会重跑 load，据此保留浏览位置） */
    private var loadedKey: Pair<Long, Boolean>? = null

    /** 翻页加载单飞：新翻页取消在途的旧页加载，防止旧请求后完成把 pager 拽回上一页 */
    private var selectJob: Job? = null
    private var extendJob: Job? = null
    private var headExhausted = false
    private var tailExhausted = false

    fun load(imageId: Long, favoritesOnly: Boolean = false) {
        val key = imageId to favoritesOnly
        if (loadedKey == key) return
        loadedKey = key
        this.favoritesOnly = favoritesOnly
        headExhausted = false
        tailExhausted = false
        viewModelScope.launch {
            val image = imageRepository.getById(imageId)
            if (image == null) {
                _state.value = ImageDetailState.NotFound
                return@launch
            }
            val ocr = imageRepository.getOcr(imageId)
            if (favoritesOnly) {
                val ids = imageRepository.favoriteTimelineIds()
                val index = ids.indexOf(imageId)
                if (index < 0) {
                    _state.value = ImageDetailState.NotFound
                    return@launch
                }
                _state.value = ImageDetailState.Ready(
                    ids = ids,
                    currentIndex = index,
                    current = image,
                    ocr = ocr,
                )
                return@launch
            }
            // 与图库网格使用同一排序，左右滑动顺序才能跟随用户选择的排列方式
            sort = settings.gallerySort.first()
            // 先以单元素窗口立即展示当前图，再补齐锚点前后的 id 窗口
            _state.value = ImageDetailState.Ready(
                ids = listOf(imageId),
                currentIndex = 0,
                current = image,
                ocr = ocr,
            )
            val window = imageRepository.timelineWindowAround(image, sort)
            headExhausted = window.anchorIndex < ImageRepository.TIMELINE_WINDOW_RADIUS
            tailExhausted = window.ids.size - window.anchorIndex - 1 < ImageRepository.TIMELINE_WINDOW_RADIUS
            val latest = _state.value as? ImageDetailState.Ready ?: return@launch
            // 补齐期间当前图已被删除切页则放弃过期窗口
            if (latest.current.id != imageId) return@launch
            _state.value = latest.copy(
                ids = window.ids,
                currentIndex = window.anchorIndex,
                totalCount = window.total.coerceAtLeast(window.ids.size),
                rankOffset = (window.rank - window.anchorIndex).coerceAtLeast(0),
            )
        }
    }

    fun selectIndex(index: Int) {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        if (index == ready.currentIndex || index !in ready.ids.indices) return
        val id = ready.ids[index]
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            val image = imageRepository.getById(id) ?: return@launch
            val ocr = imageRepository.getOcr(id)
            // 挂起期间窗口可能被扩窗平移或删除改写：以最新 state 为基准、按 id 重定位下标
            val latest = _state.value as? ImageDetailState.Ready ?: return@launch
            val latestIndex = if (latest.ids.getOrNull(index) == id) index else latest.ids.indexOf(id)
            if (latestIndex < 0) return@launch
            _state.value = latest.copy(currentIndex = latestIndex, current = image, ocr = ocr)
            maybeExtendWindow()
        }
    }

    /** 供 HorizontalPager 每页按 id 异步加载图片实体（path/mime 等） */
    suspend fun entityFor(id: Long): ImageEntity? = imageRepository.getById(id)

    fun toggleFavorite() {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        viewModelScope.launch {
            val next = !image.isFavorite
            imageRepository.setFavorite(image.id, next)
            _toast.value = if (next) "已收藏" else "已取消收藏"
            // 只局部更新当前实体，不重建窗口；收藏模式下取消收藏也留在当前页
            val latest = _state.value as? ImageDetailState.Ready ?: return@launch
            if (latest.current.id == image.id) {
                _state.value = latest.copy(current = latest.current.copy(isFavorite = next))
            }
        }
    }

    /** 导出子目录列表（保存弹窗用） */
    val exportSubdirs: StateFlow<List<String>> = settings.exportSubdirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 保存弹窗新建子目录（落盘走 App 级 appScope） */
    fun createSubdir(name: String) = settings.addExportSubdir(name)

    /**
     * 详情页「保存」：导出到 Pictures/QAlbum[/子目录]；[alsoTrash] 为真则保存后把当前图移入回收站，
     * 并像删除那样切到下一张（已无图则回调 [onExhausted] 退出）。
     */
    fun saveCurrent(subdir: String?, alsoTrash: Boolean, onExhausted: () -> Unit = {}) {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        if (!_operationBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                // Q 以下 WRITE_EXTERNAL_STORAGE 是运行时权限：未授予时 insert 会被 catch 成
                // 静默失败，这里先行拦截给出可读原因（与清理页 saveImage 同口径）
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !imageFileManager.canExportPreQ()) {
                    _toast.value = "保存失败：需要存储权限，请在系统设置中授予后重试"
                    return@launch
                }
                val ok = imageFileManager.exportToGallery(image.id, subdir)
                if (!ok) {
                    _toast.value = "保存失败"
                    return@launch
                }
                if (alsoTrash) {
                    val trashed = quarantineManager.quarantine(image.id, "保存后移入回收站")
                    _toast.value = if (trashed) "已保存并移入回收站" else "已保存（移入回收站失败）"
                    if (trashed) advanceAfterRemoval(image.id, onExhausted)
                } else {
                    _toast.value = "已保存到相册"
                }
            } finally {
                _operationBusy.value = false
            }
        }
    }

    fun prepareOpenExternal(onReady: (ExternalOpenTarget?) -> Unit) {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        viewModelScope.launch {
            val target = imageFileManager.prepareExternalView(image.id)
            if (target == null) {
                _toast.value = "无法打开第三方应用"
                onReady(null)
            } else {
                onReady(ExternalOpenTarget(target.uri, target.mimeType))
            }
        }
    }

    fun moveCurrentToTrash(options: PictureDeleteOptions = PictureDeleteOptions(), onExhausted: () -> Unit = {}) {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        if (!_operationBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val result = quarantineManager.quarantinePictures(listOf(image.id),options,"用户手动删除")
                val ok=result.selectedProcessed>0
                if (ok) {
                    _toast.value = result.message()
                    advanceAfterRemoval(image.id, onExhausted)
                } else {
                    _toast.value = result.message()
                }
            } finally {
                _operationBusy.value = false
            }
        }
    }

    fun deleteCurrentPermanently(options: PictureDeleteOptions = PictureDeleteOptions(), onExhausted: () -> Unit = {}) {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        if (!_operationBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val result=quarantineManager.deletePicturesPermanently(listOf(image.id),options)
                val ok=result.selectedProcessed>0
                if (ok) {
                    _toast.value = result.message(permanent=true)
                    advanceAfterRemoval(image.id, onExhausted)
                } else {
                    _toast.value = result.message(permanent=true)
                }
            } finally {
                _operationBusy.value = false
            }
        }
    }

    /**
     * 删除 / 移入回收站成功后：基于**最新** state 把该图从窗口移除。删的是当前图则跳上一张
     * （删的是首张则停在新的首张）；删的不是当前图（挂起期间用户已滑走）则只平移下标、
     * 不打断浏览。若已无图可显示，回调 [onExhausted]（由 UI 返回图库）。
     */
    private suspend fun advanceAfterRemoval(
        removedId: Long,
        onExhausted: () -> Unit,
    ) {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        val removedIndex = ready.ids.indexOf(removedId)
        if (removedIndex < 0) return
        val newIds = ArrayList<Long>(ready.ids.size - 1).apply {
            addAll(ready.ids.subList(0, removedIndex))
            addAll(ready.ids.subList(removedIndex + 1, ready.ids.size))
        }
        if (newIds.isEmpty()) {
            onExhausted()
            return
        }
        val newIndex = when {
            ready.currentIndex == removedIndex -> (removedIndex - 1).coerceIn(0, newIds.size - 1)
            ready.currentIndex > removedIndex -> ready.currentIndex - 1
            else -> ready.currentIndex
        }
        val (image, ocr) = if (ready.currentIndex == removedIndex) {
            val next = imageRepository.getById(newIds[newIndex])
            if (next == null) {
                onExhausted()
                return
            }
            next to imageRepository.getOcr(next.id)
        } else {
            ready.current to ready.ocr
        }
        _state.value = ready.copy(
            ids = newIds,
            currentIndex = newIndex,
            current = image,
            ocr = ocr,
            totalCount = (ready.totalCount - 1).coerceAtLeast(1),
        )
        maybeExtendWindow()
    }

    /** 滑近窗口任一端（不足 [EXTEND_THRESHOLD]）时以端点图为新锚点续一页；收藏模式 ids 本就是全量，无需扩窗 */
    private fun maybeExtendWindow() {
        if (favoritesOnly) return
        if (extendJob?.isActive == true) return
        val ready = _state.value as? ImageDetailState.Ready ?: return
        val nearHead = !headExhausted && ready.currentIndex < EXTEND_THRESHOLD
        val nearTail = !tailExhausted && ready.ids.size - ready.currentIndex - 1 < EXTEND_THRESHOLD
        if (!nearHead && !nearTail) return
        extendJob = viewModelScope.launch {
            if (nearHead) extendHead()
            if (nearTail) extendTail()
        }
    }

    private suspend fun extendHead() {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        val headId = ready.ids.firstOrNull() ?: return
        val anchor = imageRepository.getById(headId) ?: return
        val page = imageRepository.timelineIdsBefore(anchor, sort, EXTEND_PAGE)
        if (page.isEmpty()) {
            headExhausted = true
            return
        }
        val latest = _state.value as? ImageDetailState.Ready ?: return
        // 查询期间窗口头已被其他操作改写则放弃拼接（下次滑近端点会重试）
        if (latest.ids.firstOrNull() != headId) return
        if (page.size < EXTEND_PAGE) headExhausted = true
        _state.value = latest.copy(
            ids = ArrayList<Long>(page.size + latest.ids.size).apply {
                addAll(page)
                addAll(latest.ids)
            },
            currentIndex = latest.currentIndex + page.size,
            rankOffset = (latest.rankOffset - page.size).coerceAtLeast(0),
        )
    }

    private suspend fun extendTail() {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        val tailId = ready.ids.lastOrNull() ?: return
        val anchor = imageRepository.getById(tailId) ?: return
        val page = imageRepository.timelineIdsAfter(anchor, sort, EXTEND_PAGE)
        if (page.isEmpty()) {
            tailExhausted = true
            return
        }
        val latest = _state.value as? ImageDetailState.Ready ?: return
        if (latest.ids.lastOrNull() != tailId) return
        if (page.size < EXTEND_PAGE) tailExhausted = true
        _state.value = latest.copy(ids = latest.ids + page)
    }

    fun consumeToast() {
        _toast.value = null
    }

    companion object {
        /** 距窗口端点不足该数量时触发续页 */
        private const val EXTEND_THRESHOLD = 100

        /** 每次续页取的 id 数 */
        private const val EXTEND_PAGE = ImageRepository.TIMELINE_WINDOW_RADIUS
    }
}

sealed class ImageDetailState {
    data object Loading : ImageDetailState()
    data object NotFound : ImageDetailState()

    /**
     * @param ids 当前持有的时间线 id 窗口（收藏模式为全量收藏 id）
     * @param totalCount 时间线总数（COUNT 快照，随删除自减；与 [ids] 非同一快照，仅供「第 x/N 张」展示）
     * @param rankOffset `ids[0]` 在完整时间线中的 0-based 序号（近似快照，仅供展示）
     */
    data class Ready(
        val ids: List<Long>,
        val currentIndex: Int,
        val current: ImageEntity,
        val ocr: OcrTextEntity? = null,
        val totalCount: Int = ids.size,
        val rankOffset: Int = 0,
    ) : ImageDetailState()
}

data class ExternalOpenTarget(
    val uri: Uri,
    val mimeType: String,
)
