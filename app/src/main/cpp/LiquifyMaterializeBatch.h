// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

// Merge only touching horizontal tiles: never include a gap or cross a row boundary.
// Rect/Queue are structural so the production policy is testable without Qt.
namespace LiquifyMaterializeBatch {
template<class Queue> int adjacentCount(const Queue &queue, int maxWidth) {
    if (queue.empty()) return 0;
    const auto &first = queue[0];
    int width = first.width();
    int count = 1;
    while (count < int(queue.size())) {
        const auto &next = queue[count];
        if (next.y() != first.y() || next.height() != first.height() ||
            next.x() != first.x() + width || width + next.width() > maxWidth) break;
        width += next.width();
        ++count;
    }
    return count;
}
}
