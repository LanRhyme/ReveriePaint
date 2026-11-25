// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <unordered_map>
#include <utility>
#include <vector>

// Destination-space backward displacement: image(p) = original(p - field(p)).
// Tiles and the immutable source survive preview-window moves and materialization.
// No Qt/Android dependency: the production implementation is also the host test subject.
class LiquifyInverseField {
public:
    struct Point { float x = 0, y = 0; };
    // Mirror of model/LiquifyFieldResolution.kt; fixed for the entire gesture.
    static int stepForDocument(int width, int height, float brushSize) {
        int step = 1;
        const float size = std::isfinite(brushSize) ? std::max(8.f, brushSize) : 8.f;
        while (step < 16 && step * 2 <= size / 16.f) step *= 2;
        if (width <= 0 || height <= 0) return step;
        while (step < 16 && ((int64_t(width) + step - 1) / step) *
            ((int64_t(height) + step - 1) / step) > 2000000) step *= 2;
        return step;
    }
    static constexpr int TileSide = 32;
    // Per-reader 8x8 tile grid cache, valid only while the field is immutable.
    // Collision-free across up to 256x256 field nodes for both dab() and warpInverseField().
    struct SamplingCache {
        std::array<uint64_t, 64> keys;
        std::array<const Point *, 64> points = {};
        SamplingCache() { keys.fill(UINT64_MAX); }
    };

    void reset(int width = 0, int height = 0, int step = 1) {
        m_width = width;
        m_height = height;
        m_step = std::max(1, step);
        m_invStep = 1.f / float(m_step);
        m_maxFieldX = m_width > 0 ? float((m_width + m_step - 1) / m_step - 1) : 0.f;
        m_maxFieldY = m_height > 0 ? float((m_height + m_step - 1) / m_step - 1) : 0.f;
        m_tiles.clear();
        m_scratch.clear();
        m_rowSpan.clear();
    }

    Point sample(float x, float y, SamplingCache *cache = nullptr) const {
        // Clamp the field at the document edge, never at a temporary cache edge.
        if (m_width <= 0 || m_height <= 0) return {};
        x = std::clamp(x * m_invStep - .5f, 0.f, m_maxFieldX);
        y = std::clamp(y * m_invStep - .5f, 0.f, m_maxFieldY);
        const int ix = int(x), iy = int(y);
        const float fx = x - ix, fy = y - iy;
        // Almost all bilinear footprints remain within one tile: one hash lookup, not four.
        const int lx = ix & (TileSide - 1);
        const int ly = iy & (TileSide - 1);
        if (lx != TileSide - 1 && ly != TileSide - 1) {
            const Point *tile = tilePoints(ix, iy, cache);
            if (!tile) return {};
            const Point *p = tile + ly * TileSide + lx;
            return {(p[0].x + (p[1].x-p[0].x)*fx)*(1-fy) +
                    (p[TileSide].x + (p[TileSide+1].x-p[TileSide].x)*fx)*fy,
                    (p[0].y + (p[1].y-p[0].y)*fx)*(1-fy) +
                    (p[TileSide].y + (p[TileSide+1].y-p[TileSide].y)*fx)*fy};
        }
        const Point a = node(ix, iy, cache), b = node(ix + 1, iy, cache);
        const Point c = node(ix, iy + 1, cache), d = node(ix + 1, iy + 1, cache);
        return {(a.x + (b.x - a.x) * fx) * (1 - fy) + (c.x + (d.x - c.x) * fx) * fy,
                (a.y + (b.y - a.y) * fx) * (1 - fy) + (c.y + (d.y - c.y) * fx) * fy};
    }

    // Compact C1 mask, identical to the GLES kernel. A midpoint dab is bounded
    // by size * .22, so |grad(delta * mask)| <= 2 * .22 * 1.5 / 2.5 < 1.
    // Each incremental map is orientation preserving, unlike additive displacement.
    static float mask(float x, float y, float radius, float hardness = 0.f) {
        const float distance = std::hypot(x, y) / radius;
        const float r = hardness == 0.f ? std::min(1.f, distance)
            : std::clamp((distance - hardness) / (1.f - hardness), 0.f, 1.f);
        return 1.f - r * r * (3.f - 2.f * r);
    }

    void apply(float fx, float fy, float tx, float ty, float strength, float size, int mode,
               bool professional = false, float hardness = .5f) {
        if (m_width <= 0 || m_height <= 0 || !std::isfinite(fx) || !std::isfinite(fy) ||
            !std::isfinite(tx) || !std::isfinite(ty) || !std::isfinite(strength) ||
            !std::isfinite(size) || strength <= 0) return;
        size = std::max(8.f, size);
        strength = std::min(professional ? 1.f : 2.f, strength);
        const float distance = std::hypot(tx - fx, ty - fy);
        if (!std::isfinite(distance) || mode < 0 || mode > (professional ? 6 : 4)) return;
        const bool radial = mode >= 1 && mode <= 4;
        if (distance == 0 && !(professional && radial)) return;
        const float spacing = professional ? std::max(.2f, size * .04f) : size * .22f;
        const float divisions = std::ceil(distance / spacing - (professional ? .00001f : 0.f));
        if (divisions > 65536.f) return;
        const int steps = std::max(1, int(divisions));
        const float dx = (tx - fx) / steps, dy = (ty - fy) / steps;
        const float amplitude = .2f + .8f * std::min(1.f, distance / size);
        const float modernGain = !radial ? strength : strength *
            (distance == 0 ? (mode <= 2 ? 1.5f : 2.f) : distance / size * (mode <= 2 ? .8f : 1.2f)) / steps;
        const float gain = professional ? modernGain : mode == 0 ? strength / (1.f + strength) :
            strength * amplitude * (mode <= 2 ? .35f : .6f) / steps;
        for (int i = 0; i < steps; ++i) {
            dab(fx + (i + .5f) * dx, fy + (i + .5f) * dy, dx, dy, gain,
                size * (professional ? .5f : 2.5f), mode, professional, hardness);
        }
    }

    size_t tileCount() const { return m_tiles.size(); }

private:
    using Tile = std::array<Point, TileSide * TileSide>;
    static uint64_t key(int x, int y) {
        return (uint64_t(uint32_t(y >> 5)) << 32) | uint32_t(x >> 5);
    }
    const Point *tilePoints(int x, int y, SamplingCache *cache) const {
        const int tx = x >> 5, ty = y >> 5;
        const uint64_t k = (uint64_t(uint32_t(ty)) << 32) | uint32_t(tx);
        const size_t slot = size_t(tx & 7) | (size_t(ty & 7) << 3);
        if (cache && cache->keys[slot] == k) return cache->points[slot];
        const auto it = m_tiles.find(k);
        const Point *p = it == m_tiles.end() ? nullptr : it->second.data();
        if (cache) {
            cache->keys[slot] = k;
            cache->points[slot] = p;
        }
        return p;
    }
    Point node(int x, int y, SamplingCache *cache = nullptr) const {
        const Point *p = tilePoints(x, y, cache);
        return p ? p[(y & (TileSide - 1)) * TileSide + (x & (TileSide - 1))] : Point{};
    }
    void dab(float cx, float cy, float dx, float dy, float gain, float radius, int mode,
             bool professional, float hardness) {
        const int maxNodeX = (m_width + m_step - 1) / m_step - 1;
        const int maxNodeY = (m_height + m_step - 1) / m_step - 1;
        const int x0 = std::max(0, int(std::floor((cx - radius) * m_invStep - .5f)));
        const int y0 = std::max(0, int(std::floor((cy - radius) * m_invStep - .5f)));
        const int x1 = std::min(maxNodeX, int(std::ceil((cx + radius) * m_invStep - .5f)));
        const int y1 = std::min(maxNodeY, int(std::ceil((cy + radius) * m_invStep - .5f)));
        if (x1 < x0 || y1 < y0) return;
        const int width = x1 - x0 + 1;
        const int rows = y1 - y0 + 1;
        m_scratch.resize(size_t(width) * rows);
        m_rowSpan.resize(size_t(rows));
        const float h = professional && std::isfinite(hardness)
            ? std::clamp(hardness, 0.f, 1.f) * .85f : 0.f;
        const float radiusSq = radius * radius;
        const float invRadius = 1.f / radius;
        const float invOneMinusH = (h > 0.f && h < 1.f) ? 1.f / (1.f - h) : 0.f;
        const float stepF = float(m_step);
        const bool pushLike = (mode == 0 || mode == 5 || mode == 6);
        const float baseVx = (mode == 5 ? dy : (mode == 6 ? -dy : dx)) * gain;
        const float baseVy = (mode == 5 ? -dx : (mode == 6 ? dx : dy)) * gain;
        const bool needRetain = (!professional && mode == 0);
        const float radialSignGain = ((mode == 1 || mode == 3) ? -1.f : 1.f) * gain;
        SamplingCache cache;
        // Read the old generation in full before publishing any new nodes.
        for (int y = y0; y <= y1; ++y) {
            const float py = (y + .5f) * stepF;
            const float ry = py - cy;
            const float rySq = ry * ry;
            const float remSq = radiusSq - rySq;
            if (remSq <= 0.f) {
                m_rowSpan[size_t(y - y0)] = {1, 0};
                continue;
            }
            const float halfW = std::sqrt(remSq);
            int rx0 = std::max(x0, int(std::floor((cx - halfW) * m_invStep - .5f)));
            int rx1 = std::min(x1, int(std::ceil((cx + halfW) * m_invStep - .5f)));
            while (rx0 <= rx1) {
                const float rxL = (rx0 + .5f) * stepF - cx;
                if (rxL * rxL >= remSq) ++rx0;
                else break;
            }
            while (rx1 >= rx0) {
                const float rxR = (rx1 + .5f) * stepF - cx;
                if (rxR * rxR >= remSq) --rx1;
                else break;
            }
            m_rowSpan[size_t(y - y0)] = {rx0, rx1};
            if (rx1 < rx0) continue;
            Point *scratchRow = m_scratch.data() + size_t(y - y0) * width - x0;
            for (int x = rx0; x <= rx1; ++x) {
                const float px = (x + .5f) * stepF;
                const float rx = px - cx;
                const float dist = std::sqrt(rx * rx + rySq) * invRadius;
                const float r = h == 0.f ? std::min(1.f, dist)
                    : (dist <= h ? 0.f : (dist >= 1.f ? 1.f : (dist - h) * invOneMinusH));
                const float t = 1.f - r * r * (3.f - 2.f * r);
                if (t <= 0.f) {
                    scratchRow[x] = node(x, y, &cache);
                    continue;
                }
                float vx, vy;
                float retain = 1.f;
                if (pushLike) {
                    vx = baseVx * t;
                    vy = baseVy * t;
                    if (needRetain) {
                        retain = 1.f / (1.f + 2.f * std::sqrt(vx * vx + vy * vy) * invRadius);
                    }
                } else if (mode == 1 || mode == 2) {
                    const float scale = std::exp(radialSignGain * t);
                    vx = rx * (1.f - scale);
                    vy = ry * (1.f - scale);
                } else {
                    const float angle = radialSignGain * t;
                    const float c = std::cos(angle), s = std::sin(angle);
                    vx = rx - (rx * c - ry * s);
                    vy = ry - (rx * s + ry * c);
                }
                const Point old = sample(px - vx, py - vy, &cache);
                scratchRow[x] = {vx + old.x * retain, vy + old.y * retain};
            }
        }
        // Publish contiguous tile rows within each row's circle span.
        for (int y = y0; y <= y1; ++y) {
            const auto span = m_rowSpan[size_t(y - y0)];
            const int rx0 = span.first, rx1 = span.second;
            const Point *scratchRow = m_scratch.data() + size_t(y - y0) * width - x0;
            for (int x = rx0; x <= rx1;) {
                const int end = std::min(rx1 + 1, ((x >> 5) + 1) * TileSide);
                const Point *from = scratchRow + x;
                const uint64_t tk = key(x, y);
                auto it = m_tiles.find(tk);
                if (it == m_tiles.end()) {
                    bool nonzero = false;
                    for (int i = 0; i < end - x; ++i) {
                        if (from[i].x != 0.f || from[i].y != 0.f) { nonzero = true; break; }
                    }
                    if (!nonzero) { x = end; continue; }
                    it = m_tiles.try_emplace(tk, Tile{}).first;
                }
                std::copy_n(from, end - x, it->second.data() + (y & (TileSide - 1)) * TileSide + (x & (TileSide - 1)));
                x = end;
            }
        }
    }

    int m_width = 0, m_height = 0, m_step = 1;
    float m_invStep = 1.f, m_maxFieldX = 0.f, m_maxFieldY = 0.f;
    std::unordered_map<uint64_t, Tile> m_tiles;
    std::vector<Point> m_scratch;
    std::vector<std::pair<int, int>> m_rowSpan;
};
