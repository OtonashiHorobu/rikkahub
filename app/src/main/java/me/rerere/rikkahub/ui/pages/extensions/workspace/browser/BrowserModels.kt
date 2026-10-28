package me.rerere.rikkahub.ui.pages.extensions.workspace.browser

import me.rerere.workspace.WorkspaceFileEntry

/** 文件列表的排序键 */
enum class BrowserSort { NAME, SIZE, TIME }

/** 一次最多渲染多少行，超出部分走「显示更多」（dsh sidebar 的 waterline） */
const val BROWSER_PAGE_SIZE = 50

/** 内容搜索一次最多返回多少条命中 */
const val CONTENT_SEARCH_LIMIT = 100

data class Crumb(val label: String, val path: String)

/**
 * 把当前路径切成可点击的面包屑，每项都是完整前缀路径，点哪一项就回到哪一层。
 *
 * 本地工作区的路径相对文件区（`a/b`），区域根由位置栏的标签表示；
 * 远程工作区的路径是绝对路径（`/data/data/.../home`），前缀会保留 `/`。
 */
fun crumbTrail(path: String): List<Crumb> {
    val absolute = path.startsWith("/")
    val crumbs = mutableListOf<Crumb>()
    var acc = ""
    path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
        acc = when {
            acc.isNotEmpty() -> "$acc/$segment"
            absolute -> "/$segment"
            else -> segment
        }
        crumbs += Crumb(segment, acc)
    }
    return crumbs
}

/**
 * 在当前目录列表上做过滤与排序。
 *
 * [onlyChanged] 不为 null 时只保留这些路径（按环境内绝对路径匹配）的文件，
 * 目录始终排在最前，[ascending] 只作用于同一类内部。
 */
fun List<WorkspaceFileEntry>.browserView(
    query: String,
    sort: BrowserSort,
    ascending: Boolean,
    onlyChanged: Set<String>? = null,
    absolutePath: (WorkspaceFileEntry) -> String? = { null },
): List<WorkspaceFileEntry> {
    val needle = query.trim()
    val comparator: Comparator<WorkspaceFileEntry> = when (sort) {
        BrowserSort.NAME -> compareBy { entry -> entry.name.lowercase() }
        BrowserSort.SIZE -> compareBy { entry -> entry.sizeBytes }
        BrowserSort.TIME -> compareBy { entry -> entry.updatedAt }
    }
    return filter { entry ->
        val matchesQuery = needle.isEmpty() ||
            entry.name.contains(needle, ignoreCase = true) ||
            entry.path.contains(needle, ignoreCase = true)
        val matchesChanged = onlyChanged == null || when {
            entry.isDirectory -> true
            else -> absolutePath(entry)?.let { it in onlyChanged } ?: (entry.path in onlyChanged)
        }
        matchesQuery && matchesChanged
    }.sortedWith(
        compareBy<WorkspaceFileEntry> { entry -> !entry.isDirectory }
            .then(if (ascending) comparator else comparator.reversed())
    )
}

/** 内容搜索的一条命中，[path] 是相对当前存储区的路径 */
data class ContentSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)
