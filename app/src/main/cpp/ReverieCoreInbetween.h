/*
 * Pencil2D - Traditional Animation Software
 * Copyright (C) 2005-2007 Patrick Corrieri & Pascal Naidon
 * Copyright (C) 2012-2020 Matthew Chiawen Chang
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Adapted for ReveriePaint from Pencil2D core_lib/src/graphics/bitmap/inbetween.h
 */

#ifndef REVERIE_CORE_INBETWEEN_H
#define REVERIE_CORE_INBETWEEN_H

#include <QColor>
#include <QImage>

namespace ReverieInbetween
{

struct Options
{
    qreal epsilon = 1.2;             // 等值带半宽 (越大线条越粗)
    int blurPasses = 1;              // 距离场平滑次数 (抑制碎点)
    int denoiseArea = 6;             // 小于该像素数的孤立连通域被清除 (0=关闭)
    QColor strokeColor = QColor(Qt::black);
};

/** 生成 t in (0,1) 处的中间帧；a 与 b 须同尺寸，返回 Format_ARGB32_Premultiplied */
QImage interpolate(const QImage &a, const QImage &b, qreal t, const Options &options = {});

} // namespace ReverieInbetween

#endif // REVERIE_CORE_INBETWEEN_H
