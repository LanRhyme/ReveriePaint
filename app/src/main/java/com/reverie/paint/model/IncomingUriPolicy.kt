/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 外部 intent 里"这次是不是要导入文件"的判定 (纯逻辑, JVM 单测覆盖)。
 *
 * 为什么需要它: 桌面小组件用 Glance 的 `actionStartActivity` 发跳转 intent, 而 Glance 会给
 * 这个 intent 填一个 `dat=glance-action:/CALLBACK?appWidgetId=...&viewSize=...` 的 data。
 * 那串 URI 没有 content provider, 一旦被当成"用户要导入的文件"送进导入流程, 必然失败并弹
 * 一条"导入失败: 未能识别的文件格式"——用户只是点了下小组件而已。
 *
 * 所以只有真正读得出来的来源 (file / content) 才算待导入文件, 其余 (glance-action、http 等)
 * 一律不算; 带路由 extra 的导航 intent 更是根本不进导入流程 (见 MainActivity)。
 */
object IncomingUriPolicy {

    /** 可以当"待导入文件"处理的 URI scheme */
    val IMPORTABLE_SCHEMES: Set<String> = setOf("file", "content")

    fun isImportable(scheme: String?): Boolean =
        scheme != null && scheme.lowercase() in IMPORTABLE_SCHEMES
}
