/* ============================================================
 * ReverieCoreSelectionLasso.cpp - Lasso tools: magnetic lasso, free lasso select/fill/clear
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

QVector<QPoint> ReverieCore::magneticLasso(const QPoint &from, const QPoint &to, int radius)
{
    KisImageSP image = m_document;
    if (!image) {
        return {};
    }
    const int iw = image->width();
    const int ih = image->height();
    const int r = qMax(2, radius);
    // Search region: bounding box of both points plus a margin, clipped to
    // the document (the magnetic path only ever lives inside the canvas)
    QRect region(QPoint(qMin(from.x(), to.x()), qMin(from.y(), to.y())),
                 QPoint(qMax(from.x(), to.x()), qMax(from.y(), to.y())));
    region = region.adjusted(-r, -r, r, r).intersected(QRect(0, 0, iw, ih));
    const int rw = region.width();
    const int rh = region.height();
    if (rw < 3 || rh < 3) {
        return {from, to};
    }

    // Composite the current projection so the edges match what the user sees
    image->waitForDone();
    KisPaintDeviceSP proj = image->projection();
    QVector<quint8> bytes(size_t(rw) * rh * 4);
    proj->readBytes(bytes.data(), region.x(), region.y(), rw, rh);

    // Luminance (readBytes returns B,G,R,A for the RGB8 space)
    QVector<quint8> gray(size_t(rw) * rh);
    for (int i = 0; i < rw * rh; ++i) {
        const quint8 b = bytes[size_t(i) * 4];
        const quint8 g = bytes[size_t(i) * 4 + 1];
        const quint8 r = bytes[size_t(i) * 4 + 2];
        gray[i] = quint8((int(r) * 299 + int(g) * 587 + int(b) * 114) / 1000);
    }
    // Sobel magnitude edge map (stand-in for Krita's LoG intensity)
    QVector<quint8> edge(size_t(rw) * rh, 0);
    int gmax = 1;
    for (int y = 1; y < rh - 1; ++y) {
        for (int x = 1; x < rw - 1; ++x) {
            const int i00 = (y - 1) * rw + x - 1, i01 = (y - 1) * rw + x, i02 = (y - 1) * rw + x + 1;
            const int i10 = y * rw + x - 1, i12 = y * rw + x + 1;
            const int i20 = (y + 1) * rw + x - 1, i21 = (y + 1) * rw + x, i22 = (y + 1) * rw + x + 1;
            const int gx = -gray[i00] - 2 * gray[i10] - gray[i20] + gray[i02] + 2 * gray[i12] + gray[i22];
            const int gy = -gray[i00] - 2 * gray[i01] - gray[i02] + gray[i20] + 2 * gray[i21] + gray[i22];
            const int mag = qMin(255, int(std::sqrt(double(gx * gx + gy * gy)) * 0.35));
            edge[y * rw + x] = quint8(mag);
            if (mag > gmax) {
                gmax = mag;
            }
        }
    }
    if (gmax > 1) {
        for (int i = 0; i < rw * rh; ++i) {
            edge[i] = quint8(int(edge[i]) * 255 / gmax);
        }
    }
    // Widen the edge band with a 3x3 maximum filter so the path snaps onto
    // edges even when the finger drifts a couple of pixels off them
    QVector<quint8> edgeDil = edge;
    for (int y = 1; y < rh - 1; ++y) {
        for (int x = 1; x < rw - 1; ++x) {
            quint8 mx = 0;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    mx = qMax(mx, edge[(y + dy) * rw + x + dx]);
                }
            }
            edgeDil[y * rw + x] = mx;
        }
    }
    edge = edgeDil;

    // Snap the endpoints onto the nearest strong edge so the path hugs the
    // edge band instead of wandering through uniform areas (Krita's worker
    // effectively does the same: its graph only covers filtered edge tiles)
    const auto snapToEdge = [&](QPoint pt) -> QPoint {
        QPoint best = pt;
        int bestD = std::numeric_limits<int>::max();
        const int px = pt.x() - region.x();
        const int py = pt.y() - region.y();
        for (int dy = -r; dy <= r; ++dy) {
            for (int dx = -r; dx <= r; ++dx) {
                const int x = px + dx;
                const int y = py + dy;
                if (x < 0 || y < 0 || x >= rw || y >= rh) {
                    continue;
                }
                if (edge[y * rw + x] > 128) {
                    const int d = dx * dx + dy * dy;
                    if (d < bestD) {
                        bestD = d;
                        best = QPoint(x + region.x(), y + region.y());
                    }
                }
            }
        }
        return best;
    };
    const QPoint fromSnap = snapToEdge(from);
    const QPoint toSnap = snapToEdge(to);
    const QPoint start(fromSnap - region.topLeft());
    const QPoint goal(toSnap - region.topLeft());
    const int sIdx = start.y() * rw + start.x();
    const int gIdx = goal.y() * rw + goal.x();
    if (sIdx < 0 || gIdx < 0 || sIdx >= rw * rh || gIdx >= rw * rh) {
        return {from, to};
    }

    // Dijkstra over the 8-neighbour graph; edge weight follows Krita:
    // euclidean step + (255 - average edge intensity) so strong edges are
    // cheap and the path snaps to them
    const auto idx = [rw](int x, int y) { return y * rw + x; };
    QVector<double> gScore(size_t(rw) * rh, std::numeric_limits<double>::max());
    QVector<int> came(size_t(rw) * rh, -1);
    typedef std::pair<double, int> QP;
    std::priority_queue<QP, std::vector<QP>, std::greater<QP>> open;
    gScore[sIdx] = 0.0;
    open.push({0.0, sIdx});
    static const int dx8[8] = {-1, 0, 1, -1, 1, -1, 0, 1};
    static const int dy8[8] = {-1, -1, -1, 0, 0, 1, 1, 1};
    while (!open.empty()) {
        const double f = open.top().first;
        const int cur = open.top().second;
        open.pop();
        if (cur == gIdx) {
            break;
        }
        if (f > gScore[cur]) {
            continue;
        }
        const int cx = cur % rw;
        const int cy = cur / rw;
        for (int d = 0; d < 8; ++d) {
            const int nx = cx + dx8[d];
            const int ny = cy + dy8[d];
            if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) {
                continue;
            }
            const int n = idx(nx, ny);
            const qreal w = std::sqrt(double(dx8[d] * dx8[d] + dy8[d] * dy8[d])) +
                            255.0 - (edge[cur] + edge[n]) / 2.0;
            const double ng = gScore[cur] + w;
            if (ng < gScore[n]) {
                gScore[n] = ng;
                came[n] = cur;
                open.push({ng, n});
            }
        }
    }
    // Rebuild the path back from the goal
    QVector<QPoint> path;
    int cur = gIdx;
    while (cur != -1) {
        path.push_front(QPoint(cur % rw + region.x(), cur / rw + region.y()));
        if (cur == sIdx) {
            break;
        }
        cur = came[cur];
    }
    if (path.isEmpty()) {
        return {from, to};
    }
    // Keep the original finger position as the segment start (the snapped
    // endpoint is inside the path); the next segment starts there too
    if (fromSnap != from) {
        path.prepend(from);
    }
    if (toSnap != to && !path.isEmpty() && path.last() != to) {
        path.append(to);
    }
    return path;
}

void ReverieCore::lassoSelect(const QVector<QPoint> &points)
{
    // Lasso is essentially a polygon selection with many points.
    selectPolygon(points);
}


void ReverieCore::lassoFill(const QVector<QPoint> &points)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    QVector<bool> mask;
    scanlineFillPolygon(points, iw, ih, mask);

    QImage layerImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(layerImg.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    const QRgb fill = qRgba(qColor.red(), qColor.green(), qColor.blue(), 255);
    bool touched = false;
    for (int y = 0; y < ih; ++y) {
        for (int x = 0; x < iw; ++x) {
            if (mask[size_t(y) * iw + x]) {
                layerImg.setPixel(x, y, fill);
                touched = true;
            }
        }
    }
    if (touched) {
        device->writeBytes(layerImg.constBits(), 0, 0, iw, ih);
        device->setDirty();
        markDirty();
    }
}

void ReverieCore::lassoClear(const QVector<QPoint> &points)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    QVector<bool> mask;
    scanlineFillPolygon(points, iw, ih, mask);

    QImage layerImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(layerImg.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }
    bool touched = false;
    for (int y = 0; y < ih; ++y) {
        for (int x = 0; x < iw; ++x) {
            if (mask[size_t(y) * iw + x]) {
                layerImg.setPixel(x, y, 0x00000000); // transparent
                touched = true;
            }
        }
    }
    if (touched) {
        device->writeBytes(layerImg.constBits(), 0, 0, iw, ih);
        device->setDirty();
        markDirty();
    }
}

