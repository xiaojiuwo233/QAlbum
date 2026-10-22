package com.xjw.qalbum.service.cleanup

import android.os.SystemClock
import com.xjw.qalbum.data.db.AppDatabase
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.fileops.BoundFileOps
import com.xjw.qalbum.qq.QqCachePaths
import com.xjw.qalbum.qq.QqSourceScope
import com.xjw.qalbum.qq.opaqueKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 删除规划：所选的图连同同一张图的其他文件分成组（每组一起移入回收站 / 删除），并核对磁盘上的文件。只用 QAlbum 记下的
 * 精确关联（缓存文件名推出的家族键、聊天记录里同一条消息的几个 MD5），不打开 QQ 原库，也不按画面相似度关联。
 *
 * 按家族算：同一张图的原图 / 预览图 / 缩略图（含别名后缀）都在三个分桶目录里、文件名由家族键推出，每个家族把这三个目录
 * 各列一次就知道磁盘上有哪些（[com.xjw.qalbum.fileops.IFileOps.findFiles]），不再逐个核对几十个几乎都不存在的候选名；
 * 库里记在这个家族下的图（Temp 转码图、并进来的发送图片缓存）一起算。同一条消息记了几个 MD5 的，各家族并成一组。
 * 每轮只查新出现的家族与路径，两百张图通常十来次查询和特权调用。
 */
internal class PictureFamilyPlanner(private val db: AppDatabase, private val root: String, private val timer: StepTimer = StepTimer()) {
    private val base = root.trimEnd('/') + "/"

    suspend fun batches(
        ids: Collection<Long>, options: PictureDeleteOptions, bound: BoundFileOps, scope: QqSourceScope? = null,
        consume: suspend (List<PictureFileGroup>) -> Unit,
    ) {
        val seen = HashSet<String>()
        val selected = ids.toHashSet()
        val visible = if (options.includeArchived) setOf("active", "thumbnail_trash", "archived") else setOf("active", "thumbnail_trash")
        for (chunk in ids.distinct().chunked(CHUNK)) {
            currentCoroutineContext().ensureActive()
            val started = SystemClock.elapsedRealtime()
            val seeds = db.imageDao().getAnyByIds(chunk).filter { it.status in visible }
            if (seeds.isEmpty()) continue
            val batch = Batch(bound, seeds)
            if (options.linkedVersions) batch.expand() else batch.single()
            batch.completeFiles()
            val groups = batch.groups(selected, seen, visible, options, scope)
            timer.add("plan", SystemClock.elapsedRealtime() - started)
            timer.add("plan.files", batch.owner.size.toLong())
            consume(groups)
        }
    }

    /**
     * 一批所选图的规划。节点是家族（"f" + 家族键）或推不出家族键的路径（"p" + 路径），同一张图的节点并成一组；
     * [owner] 记下每个已知文件属于哪个节点。
     */
    private inner class Batch(private val bound: BoundFileOps, private val seeds: List<ImageEntity>) {
        private val parent = HashMap<String, String>()
        val owner = HashMap<String, String>()
        private val rows = HashMap<String, ImageEntity>()
        /** 磁盘状态，格式同 statFiles */
        private val states = HashMap<String, String>()
        private val families = HashSet<Long>()
        /** 分桶目录读不出的家族：所在的组不可用 */
        private val unreadable = HashSet<Long>()
        /** 列过的分桶目录与前缀：以这些前缀开头、列表里却没有的文件就是不在了，不必再逐个核对 */
        private val listed = HashMap<String, MutableSet<String>>()
        private val seedPaths = seeds.mapTo(HashSet()) { it.path }
        /** 已按路径查过库的（没入库的新文件只查一次） */
        private val lookedUp = HashSet<String>()
        private val familyQueue = ArrayDeque<Long>()
        private val pathQueue = ArrayDeque<String>()
        private val queriedPaths = HashSet<String>()
        private var uncertain = false

        private fun find(node: String): String {
            var current = node
            while (true) {
                val next = parent[current] ?: return current
                if (next == current) return current
                current = next
            }
        }

        private fun union(a: String, b: String) {
            val x = find(a)
            val y = find(b)
            if (x != y) parent[y] = x
        }

        private fun familyNode(family: Long): String {
            if (families.add(family)) familyQueue += family
            return "f$family"
        }

        /** 文件 [path] 属于 [node]；已属于别的节点的两边并起来 */
        private fun attach(path: String, node: String) {
            val known = owner[path]
            if (known == null) {
                owner[path] = node
                if (queriedPaths.add(path)) pathQueue += path
            } else if (known != node) {
                union(known, node)
            }
        }

        private fun rememberRow(row: ImageEntity) {
            rows.putIfAbsent(row.path, row)
            lookedUp += row.path
        }

        /** 不合并版本：每张只处理选中的这个文件 */
        fun single() {
            for (seed in seeds) {
                rememberRow(seed)
                owner[seed.path] = "p${seed.path}"
            }
        }

        /** 合并版本：从所选的图出发，逐轮找出同一张图的家族与文件，直到没有新的 */
        suspend fun expand() {
            for (seed in seeds) {
                rememberRow(seed)
                val family = seed.variantGroup ?: QqCachePaths.familyKey(seed.path)
                val node = if (family != null && seed.path.startsWith(base)) familyNode(family) else "p${seed.path}"
                attach(seed.path, node)
                // 并进别的家族的发送图片缓存：它自己文件名的家族也算这张图
                QqCachePaths.familyKey(seed.path)?.takeIf { it != family && seed.path.startsWith(base) }?.let { union(node, familyNode(it)) }
            }
            while (familyQueue.isNotEmpty() || pathQueue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (families.size > MAX_FAMILIES || owner.size > MAX_FILES) {
                    uncertain = true
                    return
                }
                val fresh = ArrayList(familyQueue).also { familyQueue.clear() }
                if (fresh.isNotEmpty()) {
                    timer.measure("plan.find") { findFiles(fresh) }
                    timer.measure("plan.rows") { familyRows(fresh) }
                }
                val paths = ArrayList(pathQueue).also { pathQueue.clear() }
                timer.measure("plan.sources") { linkSources(fresh, paths) }
            }
        }

        /** 各家族三个分桶目录里以它的文件名开头的文件；前缀相同的别的图按家族键排除 */
        private suspend fun findFiles(fresh: List<Long>) {
            val requests = fresh.flatMap { family -> QqCachePaths.familyStems(root, family).map { (dir, stem) -> Triple(family, dir, stem) } }
            for (page in requests.chunked(FIND_CHUNK)) {
                val lines = bound.callWithTimeout { ops -> ops.findFiles(page.map { it.second }.toTypedArray(), page.map { it.third }.toTypedArray()) }
                val failed = HashSet<Int>()
                for (line in lines) {
                    val first = line.indexOf('|')
                    val second = line.indexOf('|', first + 1)
                    if (first <= 0 || second < 0) continue
                    val (family, dir) = page.getOrNull(line.substring(0, first).toIntOrNull() ?: -1) ?: continue
                    val name = line.substring(first + 1, second)
                    val state = line.substring(second + 1)
                    if (name.isEmpty()) {
                        if (state != "M") {
                            unreadable += family
                            failed += line.substring(0, first).toInt()
                        }
                        continue
                    }
                    val path = "$dir/$name"
                    if (QqCachePaths.familyKey(path) != family) continue
                    states[path] = state
                    attach(path, familyNode(family))
                }
                page.forEachIndexed { index, (_, dir, stem) -> if (index !in failed) listed.getOrPut(dir) { HashSet() } += stem }
            }
        }

        /** 库里记在这些家族下的图；磁盘上找到但不在其中的（新文件，或记在别的家族下）再按路径查一次 */
        private suspend fun familyRows(fresh: List<Long>) {
            val images = db.imageDao()
            for (page in fresh.chunked(SQL_CHUNK)) for (row in images.getByFamilies(page)) {
                if (!row.path.startsWith(base) && row.path !in seedPaths) continue
                rememberRow(row)
                link(row)
            }
            val unknown = owner.keys.filter { it !in lookedUp && it.startsWith(base) }
            lookedUp += unknown
            for (page in unknown.chunked(SQL_CHUNK)) for (row in images.getAnyByPaths(page)) {
                rememberRow(row)
                link(row)
            }
        }

        /** 入库的图属于它记的家族，也属于它文件名推出的家族（两者不同的是并进来的发送图片缓存） */
        private fun link(row: ImageEntity) {
            val keys = listOfNotNull(row.variantGroup, QqCachePaths.familyKey(row.path)).distinct()
            if (keys.isEmpty()) return
            val node = familyNode(keys.first())
            attach(row.path, node)
            keys.drop(1).forEach { union(node, familyNode(it)) }
        }

        /** 引用这些家族 / 路径的消息（同一 MD5 合并）的其余家族与另记的路径并进来 */
        private suspend fun linkSources(fresh: List<Long>, paths: List<String>) {
            if (fresh.isEmpty() && paths.isEmpty()) return
            val sources = db.qqSourceDao()
            val byOrigin = HashMap<String, MutableList<String>>()
            suspend fun query(families: List<Long>, files: List<String>) {
                for (edge in sources.familyEdges(families, files)) byOrigin.getOrPut(edge.originId) { ArrayList() } += familyNode(edge.family)
                for (edge in sources.explicitPathEdges(families, files)) {
                    // 只认图片目录里的缓存文件与所选的图，不把聊天附件的任意路径当作图片
                    if (edge.path !in seedPaths && cacheType(edge.path) == null) continue
                    val node = QqCachePaths.familyKey(edge.path)?.let(::familyNode) ?: "p${edge.path}"
                    attach(edge.path, node)
                    byOrigin.getOrPut(edge.originId) { ArrayList() } += node
                }
            }
            for (page in fresh.chunked(SQL_CHUNK)) query(page, emptyList())
            for (page in paths.chunked(PATH_CHUNK)) query(emptyList(), page)
            for (nodes in byOrigin.values) nodes.drop(1).forEach { union(nodes.first(), it) }
        }

        /** 补齐不是由分桶目录找到的文件（库里的、另记的路径）的磁盘状态与库里的行 */
        suspend fun completeFiles() {
            if (uncertain) return
            val unknown = owner.keys.filter { it !in lookedUp }
            lookedUp += unknown
            for (page in unknown.chunked(SQL_CHUNK)) db.imageDao().getAnyByPaths(page).forEach(::rememberRow)
            for (path in owner.keys) {
                if (path in states) continue
                val slash = path.lastIndexOf('/')
                val name = path.substring(slash + 1)
                if (listed[path.substring(0, slash)]?.any { name.startsWith(it) } == true) states[path] = "M"
            }
            val missing = owner.keys.filter { it !in states }
            timer.measure("plan.stat") {
                for (page in missing.chunked(STAT_CHUNK)) {
                    currentCoroutineContext().ensureActive()
                    val result = bound.callWithTimeout { it.statFiles(page.toTypedArray()) }
                    check(result.size == page.size) { "文件状态返回不完整" }
                    page.indices.forEach { states[page[it]] = result[it] }
                }
            }
        }

        suspend fun groups(
            selected: Set<Long>, seen: MutableSet<String>, visible: Set<String>, options: PictureDeleteOptions, scope: QqSourceScope?,
        ): List<PictureFileGroup> {
            val components = owner.keys.groupBy { find(owner.getValue(it)) }.values
                .filter { paths -> paths.any { rows[it]?.id in selected } }
            val blocked = unreadable.mapTo(HashSet()) { find("f$it") }
            val shared = if (options.includeShared || uncertain) emptySet() else timer.measure("plan.contexts") { shared(components, scope) }
            val groups = ArrayList<PictureFileGroup>()
            for (paths in components) {
                val chosen = paths.mapNotNull { rows[it]?.id }.filterTo(HashSet()) { it in selected }
                val key = opaqueKey("picture-files", *paths.sorted().toTypedArray())
                if (!seen.add(key)) continue
                val records = paths.mapNotNull { rows[it] }
                // 归档页删除（includeArchived）时归档不算保护
                val protected = records.any { it.isArchived && !options.includeArchived }
                var unavailable = uncertain || records.any { it.status !in visible && it.status != "missing" } ||
                    find(owner.getValue(paths.first())) in blocked
                val assets = ArrayList<PictureAsset>()
                if (!unavailable) for (path in paths) {
                    val parts = states[path].orEmpty().split('|')
                    if (parts.firstOrNull() == "M") continue
                    if (parts.size != 4 || parts[0] != "S") { unavailable = true; continue }
                    val size = parts[1].toLongOrNull()
                    val mtime = parts[2].toLongOrNull()
                    if (size == null || mtime == null) { unavailable = true; continue }
                    val old = rows[path]
                    if (old != null && (old.size != size || old.mtime != mtime || old.status == "missing")) { unavailable = true; continue }
                    assets += PictureAsset(old?.id, path, old?.sourceType ?: cacheType(path) ?: "unknown", size, mtime, parts[3])
                }
                val block = when {
                    protected -> PictureFileGroup.Block.PROTECTED
                    paths.first() in shared -> PictureFileGroup.Block.SHARED
                    unavailable || assets.isEmpty() -> PictureFileGroup.Block.UNAVAILABLE
                    else -> null
                }
                groups += PictureFileGroup(key, chosen, assets, paths.size > 1, block)
            }
            return groups
        }

        /**
         * 「共用」的组（返回各组的第一个路径）：不限来源时，组里的图出现在不止一个会话 / 发送人组合里；限定来源时，也出现在
         * 范围之外。按家族与另记的路径各查一次，热门表情的几万条消息只做聚合。
         */
        private suspend fun shared(components: Collection<List<String>>, scope: QqSourceScope?): Set<String> {
            val sources = db.qqSourceDao()
            // 组里的家族（含磁盘上没有文件的），加上每个文件记的与文件名推出的家族（不合并版本时只有这些）
            val byRoot = families.groupBy { find("f$it") }
            val familiesOf = components.associateWith { paths ->
                byRoot[find(owner.getValue(paths.first()))].orEmpty().toHashSet() +
                    paths.flatMap { path -> listOfNotNull(rows[path]?.variantGroup, QqCachePaths.familyKey(path)) }
            }
            val allFamilies = familiesOf.values.flatten().distinct()
            val allPaths = components.flatten()
            val result = HashSet<String>()
            if (scope == null) {
                val familyRange = allFamilies.chunked(SQL_CHUNK).flatMap { sources.familyContextRange(it) }.associate { it.family to (it.low to it.high) }
                val pathRange = allPaths.chunked(PATH_CHUNK).flatMap { sources.pathContextRange(it) }.associate { it.path to (it.low to it.high) }
                for (paths in components) {
                    val ranges = familiesOf.getValue(paths).mapNotNull { familyRange[it] } + paths.mapNotNull { pathRange[it] }
                    if (ranges.any { it.first != it.second } || ranges.map { it.first }.distinct().size > 1) result += paths.first()
                }
            } else {
                val families = allFamilies.chunked(SQL_CHUNK).flatMapTo(HashSet()) { sources.familiesOutsideScope(it, scope.groupKey, scope.senderKey) }
                val outside = allPaths.chunked(PATH_CHUNK).flatMapTo(HashSet()) { sources.pathsOutsideScope(it, scope.groupKey, scope.senderKey) }
                for (paths in components) {
                    if (familiesOf.getValue(paths).any { it in families } || paths.any { it in outside }) result += paths.first()
                }
            }
            return result
        }
    }

    private fun cacheType(path: String): String? {
        if (!path.startsWith(base)) return null
        val parts = path.removePrefix(base).split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        if (parts.size == 3 && parts[0] in setOf("chatimg", "chatraw", "chatthumb") && CACHE.matches(parts[2])) return parts[0]
        if (parts.size == 2 && parts[0] in setOf("Temp", "temp") && QqCachePaths.convertedThumbnailSource(parts[1]) != null) return "chatthumb"
        return null
    }

    companion object {
        private val CACHE = Regex("Cache_-?[0-9a-fA-F]+(?:_hd)?(?:\\.(?:${QqCachePaths.ALIAS_EXTENSIONS.joinToString("|")}))?", RegexOption.IGNORE_CASE)

        /** 每批所选的图 */
        private const val CHUNK = 200

        /** 一批里的家族 / 文件超过这么多（关联异常地连成一大片）就不处理这批，避免误删 */
        private const val MAX_FAMILIES = 5_000
        private const val MAX_FILES = 20_000

        /** IN 查询分块（低版本系统 SQLite 变量上限 999；家族在来源查询里出现两次） */
        private const val SQL_CHUNK = 400
        private const val PATH_CHUNK = 300

        /** 特权调用分块：每个家族三个目录，结果几十 KB，远低于 binder 上限 */
        private const val FIND_CHUNK = 300
        private const val STAT_CHUNK = 200
    }
}
