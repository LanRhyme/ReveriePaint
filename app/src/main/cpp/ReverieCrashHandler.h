/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef REVERIE_CRASH_HANDLER_H
#define REVERIE_CRASH_HANDLER_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 初始化 Native 崩溃捕获器 (SIGSEGV / SIGABRT / SIGBUS / SIGFPE / SIGILL)。
 * @param logDir 崩溃日志持久化存放的绝对目录路径 (如 /data/user/0/.../files/crash_logs)
 * @param appVersion 应用版本号字符串
 */
void reverie_crash_handler_init(const char *logDir, const char *appVersion);

/**
 * 记录一条关键操作痕迹 (Breadcrumb) 到环形缓冲区，发生 Native 崩溃时写入报告。
 */
void reverie_crash_handler_add_breadcrumb(const char *tag, const char *message);

/**
 * 更新应用状态快照 (画布尺寸、图层数、当前工具等)。
 */
void reverie_crash_handler_update_state(const char *stateJson);

#ifdef __cplusplus
}
#endif

#endif // REVERIE_CRASH_HANDLER_H
