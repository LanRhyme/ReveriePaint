// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <unordered_map>
#include <vector>

// Destination-space backward displacement: image(p) = original(p - field(p)).
// Tiles and the immutable source survive preview-window moves and materialization.
// No Qt/Android dependency: the production implementation is also the host test subject.
class LiquifyInverseField {
public:
    struct Point { float x = 0, y = 0; };
    static constexpr int TileSide = 32;

    void reset(int width = 0, int height = 0, int step = 1) {
        m_width = width;
        m_height = height;
        m_step = std::max(1, step);
        m_tiles.clear();
        m_scratch.clear();
    }

    Point sample(float x, float y) const {
        // Clamp the field at the document edge, never at a temporary cache edge.
        if (m_width <= 0 || m_height <= 0) return {};
        x = std::clamp(x / m_step - .5f, 0.f, float((m_width + m_step - 1) / m_step - 1));
        y = std::clamp(y / m_step - .5f, 0.f, float((m_height + m_step - 1) / m_step - 1));
        const int ix = int(x), iy = int(y);
        const float fx = x - ix, fy = y - iy;
        // Almost all bilinear footprints remain within one tile: one hash lookup, not four.
        if (ix % TileSide != TileSide - 1 && iy % TileSide != TileSide - 1) {
            const auto it = m_tiles.find(key(ix, iy));
            if (it == m_tiles.end()) return {};
            const Point *p = it->second.data() + (iy % TileSide) * TileSide + ix % TileSide;
            return {(p[0].x + (p[1].x-p[0].x)*fx)*(1-fy) +
                    (p[TileSide].x + (p[TileSide+1].x-p[TileSide].x)*fx)*fy,
                    (p[0].y + (p[1].y-p[0].y)*fx)*(1-fy) +
                    (p[TileSide].y + (p[TileSide+1].y-p[TileSide].y)*fx)*fy};
        }
        const Point a = node(ix, iy), b = node(ix + 1, iy);
        const Point c = node(ix, iy + 1), d = node(ix + 1, iy + 1);
        return {(a.x + (b.x - a.x) * fx) * (1 - fy) + (c.x + (d.x - c.x) * fx) * fy,
                (a.y + (b.y - a.y) * fx) * (1 - fy) + (c.y + (d.y - c.y) * fx) * fy};
    }

    // Compact C1 mask, identical to the GLES kernel. A midpoint dab is bounded
    // by size * .22, so |grad(delta * mask)| <= 2 * .22 * 1.5 / 2.5 < 1.
    // Each incremental map is orientation preserving, unlike additive displacement.
    static float mask(float x, float y, float radius) {
        const float r = std::min(1.f, std::hypot(x, y) / radius);
        return 1.f - r * r * (3.f - 2.f * r);
    }

    void apply(float fx, float fy, float tx, float ty, float strength, float size, int mode) {
        if (m_width <= 0 || m_height <= 0 || !std::isfinite(fx) || !std::isfinite(fy) ||
            !std::isfinite(tx) || !std::isfinite(ty) || !std::isfinite(strength) ||
            !std::isfinite(size) || strength <= 0) return;
        size = std::max(8.f, size);
        strength = std::min(2.f, strength);
        const float distance = std::hypot(tx - fx, ty - fy);
        if (distance == 0) return;
        const int steps = std::max(1, int(std::ceil(distance / (size * .22f))));
        const float dx = (tx - fx) / steps, dy = (ty - fy) / steps;
        const float amplitude = .2f + .8f * std::min(1.f, distance / size);
        const float gain = mode == 0 ? strength / (1.f + strength) :
            strength * amplitude * (mode <= 2 ? .35f : .6f) / steps;
        for (int i = 0; i < steps; ++i) {
            dab(fx + (i + .5f) * dx, fy + (i + .5f) * dy, dx, dy, gain, size * 2.5f, mode);
        }
    }

    size_t tileCount() const { return m_tiles.size(); }

private:
    using Tile = std::array<Point, TileSide * TileSide>;
    static uint64_t key(int x, int y) {
        return (uint64_t(uint32_t(y / TileSide)) << 32) | uint32_t(x / TileSide);
    }
    Point node(int x, int y) const {
        const auto it = m_tiles.find(key(x, y));
        return it == m_tiles.end() ? Point{} : it->second[(y % TileSide) * TileSide + x % TileSide];
    }
    void dab(float cx, float cy, float dx, float dy, float gain, float radius, int mode) {
        const int x0 = std::max(0, int(std::floor((cx - radius) / m_step - .5f)));
        const int y0 = std::max(0, int(std::floor((cy - radius) / m_step - .5f)));
        const int x1 = std::min((m_width + m_step - 1) / m_step - 1, int(std::ceil((cx + radius) / m_step - .5f)));
        const int y1 = std::min((m_height + m_step - 1) / m_step - 1, int(std::ceil((cy + radius) / m_step - .5f)));
        if (x1 < x0 || y1 < y0) return;
        const int width = x1 - x0 + 1;
        m_scratch.resize(size_t(width) * (y1 - y0 + 1));
        // Read the old generation in full before publishing any new nodes.
        for (int y = y0; y <= y1; ++y) {
            for (int x = x0; x <= x1; ++x) {
                const float px = (x + .5f) * m_step, py = (y + .5f) * m_step;
                const float rx = px - cx, ry = py - cy;
                const float t = mask(rx, ry, radius);
                float vx = dx * gain * t, vy = dy * gain * t;
                if (mode == 1 || mode == 2) {
                    const float scale = std::exp((mode == 1 ? -1.f : 1.f) * gain * t);
                    vx = rx * (1 - scale); vy = ry * (1 - scale);
                } else if (mode == 3 || mode == 4) {
                    const float angle = (mode == 3 ? -1.f : 1.f) * gain * t;
                    const float c = std::cos(angle), s = std::sin(angle);
                    vx = rx - (rx * c - ry * s); vy = ry - (rx * s + ry * c);
                }
                const Point old = sample(px - vx, py - vy);
                // Implicit, distance-based elastic relaxation bounds accumulated strain
                // during repeated loops. Zero movement does not relax the image; the rate
                // depends on travelled brush distance, never on input/frame count.
                const float retain = mode == 0 ? 1.f / (1.f + 2.f * std::hypot(vx, vy) / radius) : 1.f;
                m_scratch[size_t(y - y0) * width + x - x0] = {vx + old.x * retain, vy + old.y * retain};
            }
        }
        // Publish contiguous tile rows. Avoid operator[] / hashing for every pixel.
        for (int y = y0; y <= y1; ++y) {
            for (int x = x0; x <= x1;) {
                const int end = std::min(x1 + 1, (x / TileSide + 1) * TileSide);
                const Point *from = m_scratch.data() + size_t(y-y0)*width + x-x0;
                auto it = m_tiles.find(key(x,y));
                if (it == m_tiles.end()) {
                    bool nonzero = false;
                    for (int i=0;i<end-x;++i) nonzero |= from[i].x != 0 || from[i].y != 0;
                    if (!nonzero) { x=end; continue; }
                    it = m_tiles.try_emplace(key(x,y), Tile{}).first;
                }
                std::copy_n(from,end-x,it->second.data()+(y%TileSide)*TileSide+x%TileSide);
                x=end;
            }
        }
    }

    int m_width = 0, m_height = 0, m_step = 1;
    std::unordered_map<uint64_t, Tile> m_tiles;
    std::vector<Point> m_scratch;
};
