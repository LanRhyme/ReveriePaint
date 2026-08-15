/*
 * Shared JNI helpers for the ReverieCoreBridge native functions.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef REVERIE_JNI_COMMON_H
#define REVERIE_JNI_COMMON_H

#include <jni.h>

class ReverieCore;

// The single core instance + Qt app (defined in reverie_jni_core.cpp)
ReverieCore *core();

#endif // REVERIE_JNI_COMMON_H
