/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * ReverieCoreFilterKernels.h - 滤镜像素内核(与KisPaintDevice解耦, 供预览管线与注册表滤镜共用)
 */
#pragma once
#include <QImage>
#include <QtGlobal>

void reverieApplyScalarKernel(QImage &img, int filterType, double p1, double p2, double p3, double p4);
void reverieApplyCurvesLutKernel(QImage &img, const quint8 *lutR256, const quint8 *lutG256, const quint8 *lutB256);
void reverieApplyGradientMapKernel(QImage &img, const qint32 *gradientLut256);
// 调整层脏区合成所需的外扩半径上限(像素)。逐像素类为0。
int reverieFilterMargin(int filterType);
