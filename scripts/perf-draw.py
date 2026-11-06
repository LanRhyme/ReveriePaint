#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""绘制卡顿的可复现测量 (放大/缩小 + 笔刷大小)。

用 debug 接收器把"标尺"（PerfTrace）打开、设定缩放档与笔刷尺寸，然后用 adb 注入
滑动笔画，最后把标尺读数值打到 logcat 里取回。全程不截图、不靠肉眼。

用法:
  scripts/perf-draw.py --serial <serial>                       # 默认矩阵
  scripts/perf-draw.py --serial <serial> --zooms 0,4,8,11 --brush 200,400
"""

import argparse
import re
import subprocess
import sys
import time

PKG = "com.reverie.paint"
RECEIVER = f"{PKG}/{PKG}.perf.ReplayVerifyReceiver"
ACTION = f"{PKG}.DEBUG_REPLAY_VERIFY"
OUT = f"/data/data/{PKG}/files/verify/perf"


def adb(serial, *args, timeout=120):
    return subprocess.run(
        ["adb"] + (["-s", serial] if serial else []) + list(args),
        capture_output=True, timeout=timeout,
    )


def shell(serial, cmd):
    return adb(serial, "shell", cmd)


def broadcast(serial, **extras):
    parts = ["am", "broadcast", "-n", RECEIVER, "-a", ACTION, "--receiver-include-background"]
    for k, v in extras.items():
        parts += ["--es", k, str(v)]
    shell(serial, " ".join(parts))


def new_canvas(serial):
    """在空白画布上测：先跑一次 makefill(newcanvas)，落地后即是绘画页"""
    broadcast(serial, path="x", out=OUT, makefill="#ff0000", newcanvas="1")
    time.sleep(13)


def prep(serial, zoom_steps, brush):
    broadcast(serial, path="x", out=OUT, perfzoom=zoom_steps, brushsize=brush)


def dump(serial):
    broadcast(serial, path="x", out=OUT, perfdump="1")
    time.sleep(0.8)
    out = adb(serial, "logcat", "-d", "-s", "RP-PERFBENCH").stdout.decode("utf-8", "replace")
    return out


def strokes(serial, count, sweep):
    """连续划 count 笔 (不留空档), 让标尺窗口里始终有绘制活动"""
    x1, y1, x2, y2, dur = sweep
    for i in range(count):
        if i < count - 1:
            shell(serial, f"input swipe {x1} {y1} {x2} {y2} {dur}")
        else:
            # 最后一笔放后台跑, 前端在笔画进行中取读数: 标尺按 ~1.8s 窗口复位,
            # 等笔画结束再读只会读到空白窗口 (曾经因此全是 0)
            adb(serial, "shell", f"input swipe {x1} {y1} {x2} {y2} {dur} &")
            time.sleep(dur / 2000.0)


def parse(text):
    """从标尺文本里取关键读数"""
    def find(pat, default=None):
        m = re.search(pat, text)
        return m.group(1) if m else default

    return {
        "render": find(r"render (full \d+/incr \d+/skip \d+)"),
        "draw": find(r"draw p95 ([\d.]+)ms avg ([\d.]+)ms"),  # 分组在下面单独取
        "draw_p95": find(r"draw p95 ([\d.]+)ms"),
        "draw_avg": find(r"draw avg ([\d.]+)ms"),
        "flip": find(r"flip ([\d.]+)/s"),
        "conn": find(r"重传 ([\d.]+)MB/帧"),
        "dirty": find(r"脏比 ([\d.]+)%"),
        "frame": find(r"frame ([\d.]+)ms p95 ([\d.]+)ms n(\d+)"),
        "frame_last": find(r"frame ([\d.]+)ms"),
        "frame_p95": find(r"frame [\d.]+ms p95 ([\d.]+)ms"),
        "present": find(r"presentWaitP50=(\d+)ms"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--zooms", default="0,4,8,11", help="zoom_in 步数 (每步 x1.2); 0 = 100%%")
    ap.add_argument("--brush", default="200", help="笔刷直径 (文档像素), 逗号分隔")
    ap.add_argument("--strokes", type=int, default=3)
    ap.add_argument("--sweep", default="700,420,1500,900,600", help="x1,y1,x2,y2,时长ms")
    ap.add_argument("--reuse-canvas", action="store_true", help="不新建画布 (复用当前页面)")
    args = ap.parse_args()

    zooms = [int(z) for z in args.zooms.split(",")]
    brushes = [int(b) for b in args.brush.split(",")]
    sweep = [int(v) for v in args.sweep.split(",")]

    if not args.reuse_canvas:
        print("[1/3] 新建空白画布…")
        new_canvas(args.serial)

    rows = []
    print("[2/3] 逐条件绘制并读取标尺…")
    for brush in brushes:
        for z in zooms:
            prep(args.serial, z, brush)
            time.sleep(2.5)
            adb(args.serial, "logcat", "-c")
            strokes(args.serial, args.strokes, sweep)
            raw = dump(args.serial)
            m = parse(raw)
            zoom_x = round(1.2 ** z, 2)
            drew = re.search(r"incr (\d+)", raw)
            n_incr = int(drew.group(1)) if drew else 0
            rows.append((zoom_x, brush, m, n_incr, raw))
            print(f"    zoom≈{zoom_x}x brush={brush}px -> incr={n_incr} "
                  f"draw p95={m['draw_p95']}ms avg={m['draw_avg']}ms "
                  f"frame p95={m['frame_p95']}ms 重传={m['conn']}MB/帧")

    print("\n[3/3] 汇总")
    hdr = f"{'zoom':>6} {'brush':>6} {'incr':>5} {'draw p95':>9} {'draw avg':>9} {'frame p95':>10} {'重传MB/帧':>10} {'脏比%':>8}"
    print(hdr)
    print("-" * len(hdr))
    for zoom_x, brush, m, n_incr, _ in rows:
        print(f"{zoom_x:>6} {brush:>6} {n_incr:>5} {str(m['draw_p95'] or '-'):>9} "
              f"{str(m['draw_avg'] or '-'):>9} {str(m['frame_p95'] or '-'):>10} "
              f"{str(m['conn'] or '-'):>10} {str(m['dirty'] or '-'):>8}")
    print("\n提示: draw p95 与 frame p95 随 zoom 明显上升 = UI 侧渲染成本;"
          "\n      重传MB/帧 随 zoom 上升 = 前缓冲上传面积变大。")


if __name__ == "__main__":
    sys.exit(main())
