/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.edit
import com.reverie.paint.core.stylus.StylusAction
import com.reverie.paint.model.SpenAirActions
import com.reverie.paint.model.SpenAirGesture

/**
 * 处理三星 S Pen 隔空手势按键事件。
 * 1. event.action != KeyEvent.ACTION_DOWN 或 event.repeatCount > 0 → false
 * 2. SpenAirGesture.resolve(...) 为 null → false (非隔空键，放行走常规链路)
 * 3. strokeActive → true (落笔中吞掉事件防状态污染，不执行动作)
 * 4. 查绑定为 "none" → false (不吞事件，放行走常规快捷键链，保证用户自定义快捷键可用)
 * 5. 触发一次触觉确认 (与侧键动作路径行为一致) 后 executeStylusAction(...) → true
 */
fun PaintViewModel.handleSpenAirKeyEvent(event: KeyEvent, strokeActive: Boolean): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
    val gesture = SpenAirGesture.resolve(
        event.keyCode,
        event.isCtrlPressed,
        event.isAltPressed,
        event.isShiftPressed,
    ) ?: return false

    if (strokeActive) return true

    val binding = spenAirBindingFor(gesture)
    if (binding.equals("none", ignoreCase = true)) return false

    // 与侧键动作路径一致: 执行动作前补触觉确认
    stylusDriver?.feedbackManager?.triggerActionConfirmation()
    executeStylusAction(StylusAction.fromActionId(binding))
    return true
}

/**
 * 查询指定手势的当前动作绑定 id。
 * 笔键类手势 (CLICK/DOUBLE_CLICK) 与侧键物理通道共用一份存储,
 * 路由到 samsungSingleClickAction / samsungDoubleClickAction。
 */
fun PaintViewModel.spenAirBindingFor(gesture: SpenAirGesture): String = when (gesture) {
    SpenAirGesture.CLICK -> samsungSingleClickAction
    SpenAirGesture.DOUBLE_CLICK -> samsungDoubleClickAction
    SpenAirGesture.SWIPE_LEFT -> spenAirSwipeLeftAction
    SpenAirGesture.SWIPE_RIGHT -> spenAirSwipeRightAction
    SpenAirGesture.SWIPE_UP -> spenAirSwipeUpAction
    SpenAirGesture.SWIPE_DOWN -> spenAirSwipeDownAction
    SpenAirGesture.CIRCLE_CW -> spenAirCircleCwAction
    SpenAirGesture.CIRCLE_CCW -> spenAirCircleCcwAction
}

/**
 * 对应 SharedPreferences 持久化 key (形如 "spenAirSwipeLeftAction")。
 * 仅服务 6 个隔空挥动类手势; 笔键类手势 (CLICK/DOUBLE_CLICK) 走侧键通道
 * (samsungSingleClickAction / samsungDoubleClickAction), 不再经此函数持久化。
 */
internal fun spenAirPrefsKey(gesture: SpenAirGesture): String = when (gesture) {
    SpenAirGesture.SWIPE_LEFT -> "spenAirSwipeLeftAction"
    SpenAirGesture.SWIPE_RIGHT -> "spenAirSwipeRightAction"
    SpenAirGesture.SWIPE_UP -> "spenAirSwipeUpAction"
    SpenAirGesture.SWIPE_DOWN -> "spenAirSwipeDownAction"
    SpenAirGesture.CIRCLE_CW -> "spenAirCircleCwAction"
    SpenAirGesture.CIRCLE_CCW -> "spenAirCircleCcwAction"
    else -> ""
}

/** 更新隔空手势动作绑定并持久化到 SharedPreferences (paint_prefs); 仅服务 6 个挥动类手势 */
fun PaintViewModel.updateSpenAirBinding(gesture: SpenAirGesture, actionId: String) {
    if (!SpenAirActions.isAllowed(actionId)) return
    when (gesture) {
        SpenAirGesture.SWIPE_LEFT -> spenAirSwipeLeftAction = actionId
        SpenAirGesture.SWIPE_RIGHT -> spenAirSwipeRightAction = actionId
        SpenAirGesture.SWIPE_UP -> spenAirSwipeUpAction = actionId
        SpenAirGesture.SWIPE_DOWN -> spenAirSwipeDownAction = actionId
        SpenAirGesture.CIRCLE_CW -> spenAirCircleCwAction = actionId
        SpenAirGesture.CIRCLE_CCW -> spenAirCircleCcwAction = actionId
        else -> return
    }
    // 与现有 prefs() 扩展惯例一致: 设置 UI 调用时 ViewModel 必然已完成 context 注入
    appContext.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
        .edit { putString(spenAirPrefsKey(gesture), actionId) }
}

/** 打开系统 S Pen 隔空操作设置页 (三级回退，FLAG_ACTIVITY_NEW_TASK) */
fun openSpenAirSystemSettings(context: Context) {
    val intents = listOf(
        Intent("com.samsung.android.service.aircommand.AIR_ACTIONS_SETTINGS"),
        Intent("com.samsung.settings.SPEN_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Throwable) {
            // 继续尝试下一个备选 Intent
        }
    }
}

/** 重置隔空手势为默认映射并持久化到 SharedPreferences (paint_prefs) */
fun PaintViewModel.resetSpenAirBindings() {
    SpenAirActions.DEFAULT_BINDINGS.forEach { (gesture, defaultAction) ->
        updateSpenAirBinding(gesture, defaultAction)
    }
}

