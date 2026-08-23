/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreFilterPreviewFx.cpp - FX 滤镜转发壳
 * (原 case 19-34 的像素处理体已并入 ReverieCoreFilterKernels.cpp 的
 * reverieApplyScalarKernel; 保留本函数与声明以维持既有链接符号)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "ReverieCoreFilterKernels.h"

/** FX filter preview cases (19-34): drop shadow, neon, ripple, twirl, etc. */
void applyFilterFxCases(QImage &img, int w, int h, int filterType,
                        double p1, double p2, double p3, double p4)
{
    Q_UNUSED(w);
    Q_UNUSED(h);
    reverieApplyScalarKernel(img, filterType, p1, p2, p3, p4);
}
