/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCrashHandler.h"

#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <dlfcn.h>
#include <unwind.h>
#include <ucontext.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <android/log.h>
#include <string.h>
#include <stdint.h>

#define TAG "ReverieCrashHandler"
#define LOG_PATH_MAX 512
#define APP_VER_MAX 64
#define STATE_MAX 2048
#define BREADCRUMB_COUNT 25
#define BREADCRUMB_LINE_MAX 128
#define ALT_STACK_SIZE (128 * 1024)

// 静态存储，避免在 signal handler 中动态分配内存 (async-signal-safe)
static char s_logDir[LOG_PATH_MAX] = {0};
static char s_appVersion[APP_VER_MAX] = {0};
static char s_appState[STATE_MAX] = {0};

struct BreadcrumbEntry {
    uint32_t timestampSec;
    char text[BREADCRUMB_LINE_MAX];
};
static BreadcrumbEntry s_breadcrumbs[BREADCRUMB_COUNT];
static volatile int s_breadcrumbIndex = 0;

static uint8_t s_altStackBuffer[ALT_STACK_SIZE];
static struct sigaction s_oldSigActions[32];
static volatile sig_atomic_t s_handlingCrash = 0;

// 简单的 async-signal-safe 辅助函数
static size_t safe_strlen(const char *s) {
    if (!s) return 0;
    size_t len = 0;
    while (s[len] != '\0') len++;
    return len;
}

static void safe_write_str(int fd, const char *str) {
    if (fd >= 0 && str) {
        write(fd, str, safe_strlen(str));
    }
}

static void safe_write_hex64(int fd, uint64_t val) {
    char buf[19];
    buf[0] = '0';
    buf[1] = 'x';
    static const char hexChars[] = "0123456789abcdef";
    for (int i = 0; i < 16; ++i) {
        buf[2 + i] = hexChars[(val >> (60 - i * 4)) & 0xF];
    }
    buf[18] = '\0';
    write(fd, buf, 18);
}

static void safe_write_uint(int fd, uint64_t val) {
    char buf[32];
    int idx = 0;
    if (val == 0) {
        write(fd, "0", 1);
        return;
    }
    char temp[32];
    int tIdx = 0;
    while (val > 0) {
        temp[tIdx++] = '0' + (val % 10);
        val /= 10;
    }
    while (tIdx > 0) {
        buf[idx++] = temp[--tIdx];
    }
    buf[idx] = '\0';
    write(fd, buf, idx);
}

static const char *get_signal_name(int signo) {
    switch (signo) {
        case SIGSEGV: return "SIGSEGV (Segmentation violation)";
        case SIGABRT: return "SIGABRT (Abort program)";
        case SIGBUS:  return "SIGBUS (Bus error)";
        case SIGFPE:  return "SIGFPE (Floating-point exception)";
        case SIGILL:  return "SIGILL (Illegal instruction)";
        case SIGTRAP: return "SIGTRAP (Trace/breakpoint trap)";
        default:      return "UNKNOWN_SIGNAL";
    }
}

// 堆栈回溯遍历
struct BacktraceState {
    void **current;
    void **end;
};

static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context *context, void *arg) {
    BacktraceState *state = static_cast<BacktraceState *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        if (state->current < state->end) {
            *state->current++ = reinterpret_cast<void *>(pc);
        } else {
            return _URC_END_OF_STACK;
        }
    }
    return _URC_NO_REASON;
}

static size_t capture_backtrace(void **buffer, size_t max) {
    BacktraceState state = {buffer, buffer + max};
    _Unwind_Backtrace(unwind_callback, &state);
    return state.current - buffer;
}

// 核心崩溃处理入口
static void native_crash_handler(int signo, siginfo_t *info, void *context) {
    // 防止多线程并发崩溃引发重入死锁
    if (__atomic_test_and_set(&s_handlingCrash, __ATOMIC_RELAXED)) {
        // 二级崩溃或竞争，直接挂起本线程
        while (1) { pause(); }
    }

    // 格式化当前时间 (仅读取系统时钟 seconds, 保持 async-signal-safe)
    time_t now = time(nullptr);
    struct tm tm_buf;
    gmtime_r(&now, &tm_buf);

    char filePath[LOG_PATH_MAX + 64];
    filePath[0] = '\0';

    if (s_logDir[0] != '\0') {
        // 构造文件名: crash_native_YYYYMMDD_HHMMSS.log
        char fileName[64];
        char numBuf[16];
        int yr = 1900 + tm_buf.tm_year;
        int mo = 1 + tm_buf.tm_mon;
        int dy = tm_buf.tm_mday;
        int hr = tm_buf.tm_hour;
        int mn = tm_buf.tm_min;
        int sc = tm_buf.tm_sec;

        // 拼接路径
        size_t dirLen = safe_strlen(s_logDir);
        memcpy(filePath, s_logDir, dirLen);
        if (filePath[dirLen - 1] != '/') {
            filePath[dirLen++] = '/';
        }
        filePath[dirLen] = '\0';

        // 简单写入名字
        const char *prefix = "crash_native_";
        size_t pLen = safe_strlen(prefix);
        memcpy(filePath + dirLen, prefix, pLen);
        size_t cur = dirLen + pLen;

        // YYYY
        filePath[cur++] = '0' + (yr / 1000) % 10;
        filePath[cur++] = '0' + (yr / 100) % 10;
        filePath[cur++] = '0' + (yr / 10) % 10;
        filePath[cur++] = '0' + (yr % 10);
        // MM
        filePath[cur++] = '0' + (mo / 10) % 10;
        filePath[cur++] = '0' + (mo % 10);
        // DD
        filePath[cur++] = '0' + (dy / 10) % 10;
        filePath[cur++] = '0' + (dy % 10);
        filePath[cur++] = '_';
        // HHMMSS
        filePath[cur++] = '0' + (hr / 10) % 10;
        filePath[cur++] = '0' + (hr % 10);
        filePath[cur++] = '0' + (mn / 10) % 10;
        filePath[cur++] = '0' + (mn % 10);
        filePath[cur++] = '0' + (sc / 10) % 10;
        filePath[cur++] = '0' + (sc % 10);
        const char *ext = ".log";
        memcpy(filePath + cur, ext, 4);
        cur += 4;
        filePath[cur] = '\0';
    }

    int fd = -1;
    if (filePath[0] != '\0') {
        fd = open(filePath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    }

    // 报告头部
    safe_write_str(fd, "===================================================\n");
    safe_write_str(fd, "          ReveriePaint Native Crash Report         \n");
    safe_write_str(fd, "===================================================\n");
    safe_write_str(fd, "App Version: ");
    safe_write_str(fd, s_appVersion[0] ? s_appVersion : "Unknown");
    safe_write_str(fd, "\nSignal: ");
    safe_write_str(fd, get_signal_name(signo));
    safe_write_str(fd, " (code=");
    safe_write_uint(fd, info ? info->si_code : 0);
    safe_write_str(fd, ")\nFault Address: ");
    safe_write_hex64(fd, reinterpret_cast<uintptr_t>(info ? info->si_addr : 0));
    safe_write_str(fd, "\n");

    // 线程信息
    safe_write_str(fd, "Crashed Thread PID: ");
    safe_write_uint(fd, getpid());
    safe_write_str(fd, ", TID: ");
    safe_write_uint(fd, gettid());
    char commBuf[64] = {0};
    int commFd = open("/proc/self/comm", O_RDONLY);
    if (commFd >= 0) {
        ssize_t n = read(commFd, commBuf, sizeof(commBuf) - 1);
        if (n > 0) {
            commBuf[n] = '\0';
            safe_write_str(fd, " (");
            // 去除末尾换行
            for (ssize_t k = 0; k < n; ++k) {
                if (commBuf[k] == '\n' || commBuf[k] == '\r') {
                    commBuf[k] = '\0';
                    break;
                }
            }
            safe_write_str(fd, commBuf);
            safe_write_str(fd, ")");
        }
        close(commFd);
    }
    safe_write_str(fd, "\n");

    // 寄存器状态 (ARM64)
#if defined(__aarch64__)
    ucontext_t *uc = static_cast<ucontext_t *>(context);
    if (uc) {
        safe_write_str(fd, "\n--- [Registers ARM64] ---\n");
        safe_write_str(fd, "pc: "); safe_write_hex64(fd, uc->uc_mcontext.pc);
        safe_write_str(fd, "  sp: "); safe_write_hex64(fd, uc->uc_mcontext.sp);
        safe_write_str(fd, "  lr: "); safe_write_hex64(fd, uc->uc_mcontext.regs[30]);
        safe_write_str(fd, "\n");
        for (int r = 0; r < 29; r += 2) {
            safe_write_str(fd, "x"); safe_write_uint(fd, r); safe_write_str(fd, ": ");
            safe_write_hex64(fd, uc->uc_mcontext.regs[r]);
            safe_write_str(fd, "  x"); safe_write_uint(fd, r + 1); safe_write_str(fd, ": ");
            safe_write_hex64(fd, uc->uc_mcontext.regs[r + 1]);
            safe_write_str(fd, "\n");
        }
    }
#endif

    // 调用栈回溯
    safe_write_str(fd, "\n--- [Native Stack Trace] ---\n");
    const size_t maxFrames = 48;
    void *frames[maxFrames];
    size_t frameCount = capture_backtrace(frames, maxFrames);

    for (size_t i = 0; i < frameCount; ++i) {
        void *pc = frames[i];
        safe_write_str(fd, "#");
        if (i < 10) safe_write_str(fd, "0");
        safe_write_uint(fd, i);
        safe_write_str(fd, " pc ");
        safe_write_hex64(fd, reinterpret_cast<uintptr_t>(pc));

        Dl_info dlinfo;
        if (dladdr(pc, &dlinfo) && dlinfo.dli_fname) {
            safe_write_str(fd, "  ");
            safe_write_str(fd, dlinfo.dli_fname);
            if (dlinfo.dli_sname) {
                safe_write_str(fd, " (");
                safe_write_str(fd, dlinfo.dli_sname);
                uintptr_t offset = reinterpret_cast<uintptr_t>(pc) - reinterpret_cast<uintptr_t>(dlinfo.dli_saddr);
                safe_write_str(fd, "+");
                safe_write_uint(fd, offset);
                safe_write_str(fd, ")");
            }
        }
        safe_write_str(fd, "\n");
    }

    // 应用状态快照
    if (s_appState[0] != '\0') {
        safe_write_str(fd, "\n--- [App State Snapshot] ---\n");
        safe_write_str(fd, s_appState);
        safe_write_str(fd, "\n");
    }

    // 最近操作轨迹 (Breadcrumbs)
    safe_write_str(fd, "\n--- [Recent Breadcrumbs] ---\n");
    int start = s_breadcrumbIndex;
    for (int k = 0; k < BREADCRUMB_COUNT; ++k) {
        int idx = (start + k) % BREADCRUMB_COUNT;
        if (s_breadcrumbs[idx].text[0] != '\0') {
            safe_write_str(fd, "- ");
            safe_write_str(fd, s_breadcrumbs[idx].text);
            safe_write_str(fd, "\n");
        }
    }

    safe_write_str(fd, "===================================================\n");

    if (fd >= 0) {
        fsync(fd);
        close(fd);
    }

    // 写入轻量 CRASH_MARKER 供下次冷启动自检查找未正常保存的草稿
    char markerPath[LOG_PATH_MAX + 32];
    markerPath[0] = '\0';
    size_t dLen = safe_strlen(s_logDir);
    if (dLen > 0 && dLen < LOG_PATH_MAX) {
        memcpy(markerPath, s_logDir, dLen);
        if (markerPath[dLen - 1] != '/') {
            markerPath[dLen++] = '/';
        }
        const char mName[] = "CRASH_MARKER";
        memcpy(markerPath + dLen, mName, sizeof(mName));
        int mfd = open(markerPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (mfd >= 0) {
            safe_write_str(mfd, "NATIVE_CRASH:");
            safe_write_uint(mfd, signo);
            safe_write_str(mfd, "\nSTATE:");
            safe_write_str(mfd, s_appState);
            safe_write_str(mfd, "\n");
            fsync(mfd);
            close(mfd);
        }
    }

    __android_log_print(ANDROID_LOG_FATAL, TAG, "CRASH DUMPED TO %s, propagating signal %d", filePath, signo);

    // 还原旧信号处理并重新发送，允许系统生成 tombstone 彻底崩溃
    sigaction(signo, &s_oldSigActions[signo], nullptr);
    kill(getpid(), signo);
}

void reverie_crash_handler_init(const char *logDir, const char *appVersion) {
    if (logDir) {
        strncpy(s_logDir, logDir, sizeof(s_logDir) - 1);
    }
    if (appVersion) {
        strncpy(s_appVersion, appVersion, sizeof(s_appVersion) - 1);
    }

    // 设置备用信号栈
    stack_t altStack;
    altStack.ss_sp = s_altStackBuffer;
    altStack.ss_size = sizeof(s_altStackBuffer);
    altStack.ss_flags = 0;
    sigaltstack(&altStack, nullptr);

    // 注册关键崩溃信号
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = native_crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;

    const int signalsToCatch[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
    for (int sig : signalsToCatch) {
        sigaction(sig, &sa, &s_oldSigActions[sig]);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Native crash handler initialized in %s", s_logDir);
}

void reverie_crash_handler_add_breadcrumb(const char *tag, const char *message) {
    if (!message) return;
    int idx = __atomic_fetch_add(&s_breadcrumbIndex, 1, __ATOMIC_RELAXED) % BREADCRUMB_COUNT;
    BreadcrumbEntry &entry = s_breadcrumbs[idx];
    entry.timestampSec = static_cast<uint32_t>(time(nullptr));

    size_t pos = 0;
    if (tag) {
        size_t tLen = safe_strlen(tag);
        if (tLen > 24) tLen = 24;
        memcpy(entry.text, tag, tLen);
        pos += tLen;
        entry.text[pos++] = ':';
        entry.text[pos++] = ' ';
    }
    size_t mLen = safe_strlen(message);
    if (pos + mLen >= BREADCRUMB_LINE_MAX - 1) {
        mLen = BREADCRUMB_LINE_MAX - 1 - pos;
    }
    memcpy(entry.text + pos, message, mLen);
    entry.text[pos + mLen] = '\0';
}

void reverie_crash_handler_update_state(const char *stateJson) {
    if (stateJson) {
        strncpy(s_appState, stateJson, sizeof(s_appState) - 1);
    }
}
