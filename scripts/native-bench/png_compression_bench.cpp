/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * PNG 压缩档位基准 (宿主 Qt, 非 Android 目标)。
 *
 * 用途: 量化 "PNG 编码" 在工程保存 (.revp) 里的耗时/体积权衡, 为图层 PNG 的
 * 编码档位选择提供数据依据。结论记录在 docs/RENDER-OPTIMIZATION.md §3.2。
 *
 * 测两条轴:
 *   1. QImage::save(..., "PNG", quality): Qt 的 quality 语义 (0=最狠压缩? 100=不压缩?)
 *   2. QImageWriter::setCompression(level): 是否直接对应 zlib deflate level
 *
 * 样本三类, 覆盖绘画软件常见的图层内容:
 *   sketch - 透明底 + 抗锯齿笔迹 (最常见)
 *   flat   - 渐变 + 实心形状 (铺色/背景层)
 *   noisy  - 伪随机噪声 (厚涂/喷枪/照片参考)
 *
 * 编译运行见同目录 run_png_bench.sh。
 */

#include <QImage>
#include <QBuffer>
#include <QElapsedTimer>
#include <QPainter>
#include <QLinearGradient>
#include <QImageWriter>
#include <cstdio>

// 透明底 + 半透明笔迹: 模拟线稿图层 (大量 alpha=0 的长游程)
static QImage makeSketch(int n)
{
    QImage img(n, n, QImage::Format_ARGB32_Premultiplied);
    img.fill(Qt::transparent);
    QPainter p(&img);
    p.setRenderHint(QPainter::Antialiasing, true);
    quint32 s = 987654321u;
    for (int i = 0; i < n / 4; ++i) {
        s = s * 1664525u + 1013904223u;
        const qreal x0 = qreal(s % quint32(n));
        const qreal y0 = qreal((s >> 8) % quint32(n));
        s = s * 1664525u + 1013904223u;
        const qreal x1 = qreal(s % quint32(n));
        const qreal y1 = qreal((s >> 8) % quint32(n));
        QPen pen(QColor(20, 20, 25, 200 + int((s >> 16) % 55)));
        pen.setWidthF(1.0 + qreal(s % 5));
        p.setPen(pen);
        p.drawLine(QPointF(x0, y0), QPointF(x1, y1));
    }
    p.end();
    return img;
}

static QImage makeFlat(int n)
{
    QImage img(n, n, QImage::Format_ARGB32_Premultiplied);
    QPainter p(&img);
    QLinearGradient g(0, 0, n, n);
    g.setColorAt(0, QColor(240, 220, 200, 255));
    g.setColorAt(1, QColor(120, 160, 200, 255));
    p.fillRect(img.rect(), g);
    p.setPen(Qt::NoPen);
    p.setBrush(QColor(30, 30, 30, 200));
    p.drawEllipse(QPointF(n / 2.0, n / 2.0), n / 4.0, n / 3.0);
    p.end();
    return img;
}

static QImage makeNoisy(int n)
{
    QImage img(n, n, QImage::Format_ARGB32_Premultiplied);
    quint32 s = 12345u;
    for (int y = 0; y < n; ++y) {
        QRgb *row = reinterpret_cast<QRgb *>(img.scanLine(y));
        for (int x = 0; x < n; ++x) {
            s = s * 1664525u + 1013904223u;
            const int v = int((s >> 24) & 0xFF);
            row[x] = qRgba(100 + (v & 63), 80 + ((v >> 2) & 63), 60 + ((v >> 4) & 63), 255);
        }
    }
    return img;
}

struct Result {
    qint64 ms;
    qint64 bytes;
    bool ok;
};

// 用 QImage::save 的 quality 参数 (Qt 的高层语义)
static Result runQuality(const QImage &img, int quality)
{
    Result r{-1, 0, false};
    for (int i = 0; i < 3; ++i) {
        QByteArray bytes;
        QBuffer buf(&bytes);
        buf.open(QIODevice::WriteOnly);
        QElapsedTimer t;
        t.start();
        const bool ok = img.save(&buf, "PNG", quality);
        const qint64 el = t.nsecsElapsed() / 1000000;
        if (!ok) return r;
        r.ok = true;
        if (i == 0) r.bytes = bytes.size();
        if (r.ms < 0 || el < r.ms) r.ms = el;
    }
    return r;
}

// 用 QImageWriter::setCompression (Qt6 新增, 疑似直接对应 zlib level)
static Result runCompression(const QImage &img, int level, int quality)
{
    Result r{-1, 0, false};
    for (int i = 0; i < 3; ++i) {
        QByteArray bytes;
        QBuffer buf(&bytes);
        buf.open(QIODevice::WriteOnly);
        QImageWriter w(&buf, "PNG");
        if (quality >= 0) w.setQuality(quality);
        w.setCompression(level);
        QElapsedTimer t;
        t.start();
        const bool ok = w.write(img);
        const qint64 el = t.nsecsElapsed() / 1000000;
        if (!ok) {
            printf("   [setCompression=%d] failed: %s\n", level, qPrintable(w.errorString()));
            return r;
        }
        r.ok = true;
        if (i == 0) r.bytes = bytes.size();
        if (r.ms < 0 || el < r.ms) r.ms = el;
    }
    return r;
}

static void report(const char *tag, const char *axis, int v, const Result &r)
{
    if (!r.ok) {
        printf("%-7s %s=%-5d FAILED\n", tag, axis, v);
        return;
    }
    printf("%-7s %s=%-5d %7lld ms  %9lld bytes\n", tag, axis, v,
           (long long)r.ms, (long long)r.bytes);
}

int main()
{
    const int N = 2048;
    struct Sample {
        const char *name;
        QImage img;
    };
    const Sample samples[] = {
        {"sketch", makeSketch(N)},
        {"flat", makeFlat(N)},
        {"noisy", makeNoisy(N)},
    };

    const int qualities[] = {-1, 0, 30, 50, 60, 70, 80, 90, 100};
    const int levels[] = {0, 1, 3, 5, 6, 9};

    for (const Sample &s : samples) {
        printf("=== %s (%dx%d) ===\n", s.name, N, N);
        for (int q : qualities) {
            report(s.name, "q", q, runQuality(s.img, q));
        }
        // QImageWriter::setCompression 扫描 (不设 quality → 默认过滤器)
        for (int lv : levels) {
            report(s.name, "level", lv, runCompression(s.img, lv, -1));
        }
        // 组合: quality=100 (=不压缩过滤器?) + 显式 level, 看是否互相覆盖
        for (int lv : levels) {
            report(s.name, "q100+lv", lv, runCompression(s.img, lv, 100));
        }
        printf("\n");
    }
    return 0;
}
