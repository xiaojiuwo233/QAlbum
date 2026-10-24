package com.xjw.qalbum.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.os.Build
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.data.repository.ImageRepository
import com.xjw.qalbum.domain.model.GallerySort
import com.xjw.qalbum.service.cleanup.ArchiveManager
import com.xjw.qalbum.service.cleanup.ImageFileManager
import com.xjw.qalbum.service.cleanup.OriginalCompressor
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
 * 滑近窗口任一端时用端点图为新锚点续页；归档页的 id 集由用户手动归档产生、规模小，仍全量装载。
 * 当前图的完整实体按 index 用 [ImageRepository.getById] 按需加载。
 */
class ImageDetailViewModel(
    private val imageRepository: ImageRepository,
    private val imageFileManager: ImageFileManager,
    private val quarantineManager: QuarantineManager,
    private val archive: ArchiveManager,
    private val settings: SettingsRepository,
    compressor: OriginalCompressor,
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
    /** 从归档页打开：只在归档的图里左右滑动 */
    private var archiveOnly = false
    private var sort: GallerySort = GallerySort.DATE_DESC
    /** 与图库同一设置：合并显示时窗口里只有家族代表图，当前页可在各版本间切换 */
    private var stacked = false

    /** 已按该参数加载过（配置变更后 Screen 的 LaunchedEffect 会重跑 load，据此保留浏览位置） */
    private var loadedKey: Pair<Long, Boolean>? = null

    /** 翻页加载单飞：新翻页取消在途的旧页加载，防止旧请求后完成把 pager 拽回上一页 */
    private var selectJob: Job? = null
    private var extendJob: Job? = null
    private var headExhausted = false
    private var tailExhausted = false

    init {
        // 压缩或还原替换了文件：当前显示的图（及其版本条）换成新的大小与压缩状态
        viewModelScope.launch {
            compressor.changes.collect { ids ->
                val ready = _state.value as? ImageDetailState.Ready ?: return@collect
                if (ids.none { id -> id == ready.current.id || ready.variants.any { it.id == id } }) return@collect
                val current = imageRepository.getById(ready.current.id) ?: return@collect
                val variants = ready.variants.map { variant -> if (variant.id in ids) imageRepository.getById(variant.id) ?: variant else variant }
                val latest = _state.value as? ImageDetailState.Ready ?: return@collect
                if (latest.current.id == current.id) _state.value = latest.copy(current = current, variants = variants)
            }
        }
    }

    fun load(imageId: Long, archiveOnly: Boolean = false) {
        val key = imageId to archiveOnly
        if (loadedKey == key) return
        loadedKey = key
        this.archiveOnly = archiveOnly
        headExhausted = false
        tailExhausted = false
        // 从网格点开的图先直接显示（同步，页面第一帧就有），数据库查完再补齐版本与前后的图
        OpeningImage.peek(imageId)?.let { opened ->
            if (_state.value !is ImageDetailState.Ready) {
                _state.value = ImageDetailState.Ready(ids = listOf(imageId), currentIndex = 0, current = opened)
            }
        }
        viewModelScope.launch {
            val image = imageRepository.getById(imageId)
            if (image == null) {
                _state.value = ImageDetailState.NotFound
                return@launch
            }
            stacked = !archiveOnly && settings.galleryStackVariants.first()
            if (archiveOnly) {
                val ids = imageRepository.archiveTimelineIds(archive.pageStatuses.first())
                val index = ids.indexOf(imageId)
                if (index < 0) {
                    _state.value = ImageDetailState.NotFound
                    return@launch
                }
                _state.value = ImageDetailState.Ready(
                    ids = ids,
                    currentIndex = index,
                    current = image,
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
                variants = familyOf(image),
            )
            val window = imageRepository.timelineWindowAround(image, sort, stacked)
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
            val variants = familyOf(image)
            // 挂起期间窗口可能被扩窗平移或删除改写：以最新 state 为基准、按 id 重定位下标
            val latest = _state.value as? ImageDetailState.Ready ?: return@launch
            val latestIndex = if (latest.ids.getOrNull(index) == id) index else latest.ids.indexOf(id)
            if (latestIndex < 0) return@launch
            _state.value = latest.copy(currentIndex = latestIndex, current = image, variants = variants)
            maybeExtendWindow()
        }
    }

    /** 供 HorizontalPager 每页按 id 异步加载图片实体（path/mime 等） */
    suspend fun entityFor(id: Long): ImageEntity? = imageRepository.getById(id)

    /** 合并显示时取这张图的全部版本（原图 / 预览图 / 缩略图）；不足两个版本返回空 */
    private suspend fun familyOf(image: ImageEntity): List<ImageEntity> {
        val group = image.variantGroup ?: return emptyList()
        if (!stacked) return emptyList()
        return imageRepository.familyMembers(group).takeIf { it.size >= 2 }.orEmpty()
    }

    /** 切换当前页显示的版本（底部版本条） */
    fun selectVariant(id: Long) {
        val ready = _state.value as? ImageDetailState.Ready ?: return
        val target = ready.variants.firstOrNull { it.id == id } ?: return
        if (target.id == ready.current.id) return
        _state.value = ready.copy(current = target)
    }

    /**
     * 归档 / 取消归档当前图（连同同一张图的其他版本）。之后若它不再属于这个看图列表（不显示归档时在图库里归档、
     * 在归档页取消归档），像删除那样切到下一张，已无图则回调 [onExhausted]；否则只局部翻转标记。
     */
    fun toggleArchive(onExhausted: () -> Unit = {}) {
        val image = (_state.value as? ImageDetailState.Ready)?.current ?: return
        if (!_operationBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val pageId = currentPageId() ?: image.id
                val next = !image.isArchived
                if (next) archive.archive(listOf(image.id)) else archive.unarchive(listOf(image.id))
                _toast.value = if (next) "已归档" else "已取消归档"
                val leaves = if (archiveOnly) !next else next && !settings.archiveVisible.first()
                if (leaves) {
                    afterRemoval(pageId, image, onExhausted)
                    return@launch
                }
                val latest = _state.value as? ImageDetailState.Ready ?: return@launch
                if (latest.current.id == image.id) {
                    fun flip(entity: ImageEntity) =
                        if (entity.id == image.id || (image.variantGroup != null && entity.variantGroup == image.variantGroup)) entity.copy(isArchived = next) else entity
                    _state.value = latest.copy(current = flip(latest.current), variants = latest.variants.map(::flip))
                }
            } finally {
                _operationBusy.value = false
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
                    _toast.value = "保存失败：请在系统设置中授予存储权限"
                    return@launch
                }
                val ok = imageFileManager.exportToGallery(image.id, subdir)
                if (!ok) {
                    _toast.value = "保存失败"
                    return@launch
                }
                if (alsoTrash) {
                    val pageId = currentPageId() ?: image.id
                    val trashed = quarantineManager.quarantinePictures(
                        listOf(image.id), PictureDeleteOptions(includeArchived = archiveOnly), "保存后移入回收站",
                    ).selectedProcessed > 0
                    _toast.value = if (trashed) "已保存并移入回收站" else "已保存（移入回收站失败）"
                    if (trashed) afterRemoval(pageId, image, onExhausted)
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
                val pageId = currentPageId() ?: image.id
                val result = quarantineManager.quarantinePictures(listOf(image.id),options.copy(includeArchived = archiveOnly),"用户手动删除")
                val ok=result.selectedProcessed>0
                if (ok) {
                    _toast.value = result.message()
                    afterRemoval(pageId, image, onExhausted)
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
                val pageId = currentPageId() ?: image.id
                val result=quarantineManager.deletePicturesPermanently(listOf(image.id),options.copy(includeArchived = archiveOnly))
                val ok=result.selectedProcessed>0
                if (ok) {
                    _toast.value = result.message(permanent=true)
                    afterRemoval(pageId, image, onExhausted)
                } else {
                    _toast.value = result.message(permanent=true)
                }
            } finally {
                _operationBusy.value = false
            }
        }
    }

    private fun currentPageId(): Long? =
        (_state.value as? ImageDetailState.Ready)?.let { it.ids.getOrNull(it.currentIndex) }

    /**
     * 删除后的去向：合并显示时若这张图还有其他版本留着（只删了其中一个版本），留在原位改显示
     * 现在最大的版本；整组都没了才像普通删除那样移出窗口。
     */
    private suspend fun afterRemoval(pageId: Long, removed: ImageEntity, onExhausted: () -> Unit) {
        val group = removed.variantGroup
        if (stacked && group != null) {
            val remaining = imageRepository.familyMembers(group)
            val representative = remaining
                .filter { it.status == "active" && it.sourceType != "chatthumb" }
                .maxWithOrNull(compareBy<ImageEntity> { it.size }.thenByDescending { it.id })
            val ready = _state.value as? ImageDetailState.Ready
            val index = ready?.ids?.indexOf(pageId) ?: -1
            if (representative != null && ready != null && index >= 0) {
                val ids = ready.ids.toMutableList().also { it[index] = representative.id }
                val onPage = ready.currentIndex == index
                _state.value = ready.copy(
                    ids = ids,
                    current = if (onPage) representative else ready.current,
                    variants = if (onPage) remaining.takeIf { it.size >= 2 }.orEmpty() else ready.variants,
                )
                return
            }
        }
        advanceAfterRemoval(pageId, onExhausted)
    }

    /**
     * 删除 / 移入回收站成功后：基于**最新** state 把该图从窗口移除。删的是当前图则停在原位置，
     * 即显示下一张（删的是末张才退到上一张）；删的不是当前图（挂起期间用户已滑走）则只平移下标、
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
            ready.currentIndex == removedIndex -> removedIndex.coerceAtMost(newIds.size - 1)
            ready.currentIndex > removedIndex -> ready.currentIndex - 1
            else -> ready.currentIndex
        }
        val image = if (ready.currentIndex == removedIndex) {
            imageRepository.getById(newIds[newIndex]) ?: run {
                onExhausted()
                return
            }
        } else {
            ready.current
        }
        val variants = if (ready.currentIndex == removedIndex) familyOf(image) else ready.variants
        _state.value = ready.copy(
            ids = newIds,
            currentIndex = newIndex,
            current = image,
            variants = variants,
            totalCount = (ready.totalCount - 1).coerceAtLeast(1),
        )
        maybeExtendWindow()
    }

    /** 滑近窗口任一端（不足 [EXTEND_THRESHOLD]）时以端点图为新锚点续一页；归档页 ids 本就是全量，无需扩窗 */
    private fun maybeExtendWindow() {
        if (archiveOnly) return
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
        val page = imageRepository.timelineIdsBefore(anchor, sort, EXTEND_PAGE, stacked)
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
        val page = imageRepository.timelineIdsAfter(anchor, sort, EXTEND_PAGE, stacked)
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
     * @param ids 当前持有的时间线 id 窗口（归档页为全量归档 id）
     * @param totalCount 时间线总数（COUNT 快照，随删除自减；与 [ids] 非同一快照，仅供「第 x/N 张」展示）
     * @param rankOffset `ids[0]` 在完整时间线中的 0-based 序号（近似快照，仅供展示）
     */
    data class Ready(
        val ids: List<Long>,
        val currentIndex: Int,
        val current: ImageEntity,
        val totalCount: Int = ids.size,
        val rankOffset: Int = 0,
        /** 当前页这张图的全部版本（合并显示时 ≥2 个才有）；[current] 是其中正在显示的那个 */
        val variants: List<ImageEntity> = emptyList(),
    ) : ImageDetailState()
}

data class ExternalOpenTarget(
    val uri: Uri,
    val mimeType: String,
)
