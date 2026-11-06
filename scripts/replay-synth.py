#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""合成录制 → 回放保真度用例 (填充 / 圈涂 / 变换 的可判定测试)。

`replay-verify.py` 用"真工程 vs 回放产物"做判定, 适合查已经发生的错误; 但像填充、
圈涂、变换这类工具操作, 期望结果是**可以算出来**的 —— 于是造一份录制, 让它的
文档层直接等于"正确回放应当得到的样子", 再让回放去跑, 就能把每个操作单独钉住。

用法:
  scripts/replay-synth.py --serial <adb序列号>            # 跑全部用例
  scripts/replay-synth.py --serial <adb序列号> --only fill_whole

退出码 0 = 全部用例在容差内一致。
"""

import argparse
import io
import json
import math
import os
import struct
import subprocess
import sys
import time
import zipfile

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

PKG = "com.reverie.paint"
FILES = "/data/data/%s/files" % PKG

# ---- 事件编码 (与 Kotlin 侧 RecordingEvents/RecordingBuffer 对齐) ----
STROKE_START, STROKE_MOVE, STROKE_END = 0x01, 0x02, 0x03
CONTEXT = 0x10
LAYER_OP = 0x20
TOOL_OP = 0x30

T_FILL_V4 = 52
T_ENCLOSE_FILL = 48
T_TRANSFORM = 7
T_TRANSFORM_LAYERS = 33
T_PRESET_SELECT_V2 = 50
T_SESSION_START = 51


def varint(v):
    out = bytearray()
    v = int(v)
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


class Ev:
    """事件流构造器: 每个事件 = type u8 + dt varint + payload"""

    def __init__(self):
        self.buf = bytearray()
        self.count = 0
        self.ms = 0

    def _head(self, typ, dt):
        self.buf.append(typ)
        self.buf += varint(dt)
        self.ms += dt
        self.count += 1

    def tool(self, op, payload=b"", dt=16):
        self._head(TOOL_OP, dt)
        self.buf.append(op)
        self.buf += payload

    # ---- 具体操作 ----
    def session_start(self, undo_limit=50, dt=0):
        self.tool(T_SESSION_START, struct.pack("<H", undo_limit), dt=dt)

    def context(self, layer=1, color="#000000", tool_mode=0, preset=0xFFFF, size=20.0,
                opacity=1.0, flow=1.0, composite="normal", dt=8):
        p = bytearray()
        p += struct.pack("<B", tool_mode)
        p += struct.pack("<H", preset if preset >= 0 else 0xFFFF)
        p += struct.pack("<fff", size, opacity, flow)
        for s in (composite, color):
            raw = s.encode()
            p += varint(len(raw)) + raw
        p += struct.pack("<H", layer)
        self._head(CONTEXT, dt)
        self.buf += p

    def fill_v4(self, x, y, color, tol=0, merged=1, expand=0, feather=0, close_gap=0, dt=16):
        p = bytearray()
        p += struct.pack("<ff", x, y)
        p += struct.pack("<H", tol)
        p += struct.pack("<B", 1 if merged else 0)
        raw = color.encode()
        p += varint(len(raw)) + raw
        p += struct.pack("<H", expand & 0xFFFF)
        p += struct.pack("<HH", feather, close_gap)
        self.tool(T_FILL_V4, p, dt=dt)

    def enclose_fill(self, points, argb, tol=0, merged=1, expand=0, feather=0,
                     close_gap=0, method=0, include_contour=0, dt=16):
        p = bytearray()
        p += struct.pack("<H", len(points))
        for (x, y) in points:
            p += struct.pack("<ff", x, y)
        p += struct.pack("<H", tol)
        p += struct.pack("<B", 1 if merged else 0)
        p += struct.pack("<h", expand)
        p += struct.pack("<HH", feather, close_gap)
        p += struct.pack("<B", method)
        p += struct.pack("<I", argb & 0xFFFFFFFF)
        p += struct.pack("<B", 1 if include_contour else 0)
        self.tool(T_ENCLOSE_FILL, p, dt=dt)

    def transform(self, xscale=1.0, yscale=1.0, xshear=0.0, yshear=0.0, rotation=0.0,
                  xtranslate=0.0, ytranslate=0.0, origin_x=-1.0, origin_y=-1.0,
                  layers=None, dt=16):
        if layers:
            p = bytearray()
            p += struct.pack("<H", len(layers))
            for li in layers:
                p += struct.pack("<H", li)
            self.tool(T_TRANSFORM_LAYERS, bytes(p), dt=dt)
        p = bytearray()
        for v in (xscale, yscale, xshear, yshear, rotation, xtranslate, ytranslate,
                  origin_x, origin_y):
            p += struct.pack("<d", v)
        self.tool(T_TRANSFORM, bytes(p), dt=dt)

    def undo(self, dt=16):
        self.tool(36, b"", dt=dt)

    def redo(self, dt=16):
        self.tool(37, b"", dt=dt)

    def gradient_v3(self, x1, y1, x2, y2, stops, gtype=0, repeat=0, reverse=False,
                    opacity=1.0, dither=False, blend="normal", dt=16):
        """stops = [(pos 0..1, 0xAARRGGBB), ...] 由左到右"""
        p = bytearray()
        p += struct.pack("<ffff", x1, y1, x2, y2)
        p += struct.pack("<BBB", gtype, repeat, 1 if reverse else 0)
        p += struct.pack("<f", opacity)
        p += struct.pack("<B", 1 if dither else 0)
        raw = blend.encode()
        p += varint(len(raw)) + raw
        p += struct.pack("<H", len(stops))
        for pos, color in stops:
            p += struct.pack("<fI", pos, color & 0xFFFFFFFF)
        self.tool(49, bytes(p), dt=dt)

    def preset(self, idx, name, preset_id, md5, dt=8):
        p = bytearray()
        p += struct.pack("<H", idx & 0xFFFF)
        for s in (name, preset_id, md5):
            raw = s.encode()
            p += varint(len(raw)) + raw
        self.tool(T_PRESET_SELECT_V2, bytes(p), dt=dt)


def build_recording(ev: Ev, w, h, snapshot=None):
    events = bytes(ev.buf)
    rec = bytearray()
    rec += b"REVPREC1"
    rec += struct.pack("<HHH", 2, w, h)   # version, docW, docH
    rec += struct.pack("<B", 1 if snapshot else 0)
    rec += struct.pack("<II", ev.count, ev.ms)
    rec += struct.pack("<I", len(events))
    rec += events
    if snapshot:
        rec += struct.pack("<Q", len(snapshot))
        rec += snapshot
    return bytes(rec)


LAYERS_XML = """<?xml version="1.0"?>
<layers>
 <paintlayer name="背景" visible="1" opacity="255" compositeop="normal" locked="1" alpha_locked="1" \
clipped="0" inherit_alpha="0" color_label="0" x="0" y="0" background="1" filename="layer_000.png"/>
 <paintlayer name="颜料图层 1" visible="1" opacity="255" compositeop="normal" locked="0" alpha_locked="0" \
clipped="0" inherit_alpha="0" color_label="0" x="0" y="0" background="0" filename="layer_001.png"/>
</layers>
"""


def meta_json(w, h):
    def layer(name, bg):
        return {
            "name": name, "visible": True, "opacity": 1.0, "blendMode": "normal",
            "locked": bg, "alphaLocked": bg, "clipped": False, "alphaInherited": False,
            "isGroup": False, "depth": 0, "colorLabel": 0, "background": bg,
            "isStrokeLayer": False, "strokeSize": 6, "strokeColor": 4278190080.0,
            "strokePosition": 0, "strokeOpacity": 100, "nodeType": 0,
        }

    return {
        "version": 1, "appName": "ReveriePaint", "width": w, "height": h,
        "colorMode": "RGB", "colorDepth": 8, "xRes": 300.0, "yRes": 300.0,
        "createdTime": "2026-11-06T12:00:00", "modifiedTime": "2026-11-06T12:00:00",
        "selectedLayerIndex": 1, "strokeCount": 0, "drawDurationSeconds": 0,
        "layers": [layer("背景", True), layer("颜料图层 1", False)],
        "animation": {"framerate": 24, "playbackStart": 0, "playbackEnd": 0,
                      "currentTime": 0, "keyframeTags": [], "lastFrameHolds": {}},
    }


def make_snapshot_revp(template, path, paint_layer: Image.Image):
    """把"回放起点画面"写成一份 .revp, 供录制内嵌为快照。"""
    write_revp(template, path, paint_layer, b"")


def write_revp(template, path, expected_paint: Image.Image, recording: bytes):
    """以一份真实工程为容器, 只替换 recording / 颜料图层 / 预览图。

    自己从零拼 .revp 容器很容易在细节上与引擎的加载器对不上 (mimetype 取值、
    meta/layers 的字段集), 于是这里直接拿真工程当模板 —— 保证"容器"是引擎认可的,
    差异只来自我们要测的录制与像素。
    """
    with zipfile.ZipFile(template) as z:
        items = [(i.filename, z.read(i.filename)) for i in z.infolist()]
    paint = io.BytesIO()
    expected_paint.save(paint, format="PNG")
    thumb = Image.new("RGBA", (256, 181), (255, 255, 255, 255))
    tb = io.BytesIO()
    thumb.save(tb, format="PNG")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for name, data in items:
            if name == "recording":
                data = recording
            elif name == "layer_001.png":
                data = paint.getvalue()
            elif name in ("preview.png", "thumbnail.png"):
                data = tb.getvalue()
            z.writestr(name, data)


def fmt_of(img):
    return "RGBA" if img.mode == "RGBA" else "L"


def rgba_solid(w, h, rgba):
    return Image.new("RGBA", (w, h), rgba)


# ---- 用例 ----

def case_fill_whole(w, h):
    """整画布填充: 透明层开局, 一次 tol=0 的填充应当把整层刷成纯色"""
    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#ff0000")
    ev.fill_v4(w // 2, h // 2, "#ff0000", tol=0, expand=0, feather=0, close_gap=0)
    expected = rgba_solid(w, h, (255, 0, 0, 255))
    return "fill_whole", ev, expected, 0.001


TRI = lambda w, h: [(w * 0.2, h * 0.8), (w * 0.5, h * 0.15), (w * 0.8, h * 0.8)]


def _polygon_mask(w, h, pts, grow=0):
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    if grow:
        from PIL import ImageFilter
        mask = mask.filter(ImageFilter.MaxFilter(2 * grow + 1))
    return mask


def case_enclose_in_outline(w, h, template, workdir):
    """圈涂填充(仅透明区域): 快照里有一个黑色方框轮廓, 用比它大的圈涂多边形
    在框内落笔 —— 期望 = 框内透明区域被画笔色填满 (轮廓本身不填)。

    这是真实使用场景的最小化: 圈涂的意义就是"沿线条围出的区域填色"。
    """
    x0, y0, x1, y1 = int(w * 0.3), int(h * 0.25), int(w * 0.7), int(h * 0.75)
    base = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    d.rectangle([x0, y0, x1, y1], outline=(0, 0, 0, 255), width=6)

    # 圈涂多边形: 比方框大一圈, 覆盖框内区域
    poly = [(x0 - 20, y0 - 20), (x1 + 20, y0 - 20), (x1 + 20, y1 + 20), (x0 - 20, y1 + 20)]
    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#ff0000")
    ev.enclose_fill(poly, 0xFFFF0000, tol=1, expand=0, feather=0, close_gap=0, method=2)

    # 期望: 快照内容 + 框内透明区域(6px 描边以内, 去掉描边本身)填红
    interior = Image.new("L", (w, h), 0)
    ImageDraw.Draw(interior).rectangle([x0 + 6, y0 + 6, x1 - 6, y1 - 6], fill=255)
    expected = base.copy()
    expected.paste((255, 0, 0, 255), mask=interior)
    snapshot = os.path.join(workdir, "snap_enclose_outline.revp")
    make_snapshot_revp(template, snapshot, base)
    with open(snapshot, "rb") as f:
        snap_bytes = f.read()
    return "enclose_in_outline", ev, expected, 0.03, snap_bytes


def case_fill_tolerance(w, h, template, workdir):
    """容差填充: 快照左半红右半蓝, 在红区点一下 -> 期望整块红被覆盖, 蓝区不动"""
    split = w // 2
    base = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(base).rectangle([0, 0, split, h], fill=(200, 30, 30, 255))
    ImageDraw.Draw(base).rectangle([split, 0, w, h], fill=(30, 30, 200, 255))

    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#00ff00")
    ev.fill_v4(split // 2, h // 2, "#00ff00", tol=0, expand=0, feather=0, close_gap=0)

    expected = base.copy()
    ImageDraw.Draw(expected).rectangle([0, 0, split - 1, h], fill=(0, 255, 0, 255))
    snapshot = os.path.join(workdir, "snap_fill_tolerance.revp")
    make_snapshot_revp(template, snapshot, base)
    with open(snapshot, "rb") as f:
        snap_bytes = f.read()
    return "fill_tolerance", ev, expected, 0.01, snap_bytes


def _snapshot_case(w, h, template, workdir, name, base, ev, expected, tol):
    snapshot = os.path.join(workdir, "snap_%s.revp" % name)
    make_snapshot_revp(template, snapshot, base)
    with open(snapshot, "rb") as f:
        return name, ev, expected, tol, f.read()


def _opaque_blocks(w, h):
    """不透明双色块底板: 左红右蓝, 覆盖整幅 (排除 alpha 语义干扰, 只测填充/撤销/重做本身)"""
    base = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    split = w // 2
    ImageDraw.Draw(base).rectangle([0, 0, split - 1, h], fill=(200, 30, 30, 255))
    ImageDraw.Draw(base).rectangle([split, 0, w - 1, h], fill=(30, 30, 200, 255))
    return base, split


def _filled_left(w, h, base, split):
    out = base.copy()
    ImageDraw.Draw(out).rectangle([0, 0, split - 1, h], fill=(0, 255, 0, 255))
    return out


def case_undo_fill(w, h, template, workdir):
    """填充后撤销: 期望回到快照画面 (验证 T_UNDO 撤销的确实是这条填充)"""
    base, split = _opaque_blocks(w, h)
    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#00ff00")
    ev.fill_v4(split // 2, h // 2, "#00ff00", tol=0)
    ev.undo()
    return _snapshot_case(w, h, template, workdir, "undo_fill", base, ev, base.copy(), 0.01)


def case_redo_fill(w, h, template, workdir):
    """填充 → 撤销 → 重做: 期望最终是填充后画面"""
    base, split = _opaque_blocks(w, h)
    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#00ff00")
    ev.fill_v4(split // 2, h // 2, "#00ff00", tol=0)
    ev.undo()
    ev.redo()
    return _snapshot_case(w, h, template, workdir, "redo_fill", base, ev,
                          _filled_left(w, h, base, split), 0.01)


def _transparent_mask(img):
    alpha = img.convert("RGBA").split()[3]
    return alpha.point(lambda v: 255 if v < 8 else 0)


def case_gradient(w, h):
    """线性渐变: 左黑右白 (opacity 1, 无抖动) -> 期望 = 水平线性插值"""
    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#000000")
    ev.gradient_v3(0, 0, w - 1, 0, [(0.0, 0xFF000000), (1.0, 0xFFFFFFFF)], dither=False)
    expected = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = expected.load()
    for x in range(w):
        v = int(round(255.0 * x / max(1, w - 1)))
        for y in range(h):
            px[x, y] = (v, v, v, 255)
    return "gradient_linear", ev, expected, 0.03


def case_enclose_transparent(w, h):
    """圈涂填充 (区域模式=仅透明区域): 空白层上落到"多边形内部"。
    区域模式 method=2 = SelectRegionsFilledWithTransparent, 用画笔色填充。"""
    pts = TRI(w, h)
    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#00ff00")
    ev.enclose_fill(pts, 0xFF00FF00, tol=0, expand=0, feather=0, close_gap=0, method=2)
    expected = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    expected.paste((0, 255, 0, 255), mask=_polygon_mask(w, h, pts))
    return "enclose_transparent", ev, expected, 0.02


def case_enclose_expand(w, h):
    """圈涂 + 缩进 10px: 期望 = 多边形按 10px 膨胀后的区域 (验证 expand 是否被录制/回放)"""
    pts = TRI(w, h)
    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#ff0000")
    ev.enclose_fill(pts, 0xFFFF0000, tol=0, expand=10, feather=0, close_gap=0, method=2)
    expected = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    expected.paste((255, 0, 0, 255), mask=_polygon_mask(w, h, pts, grow=10))
    return "enclose_expand", ev, expected, 0.05


def case_transform_translate(w, h):
    """整层绿底 + 纯平移: 期望 = 绿底整体位移 (空出来的地方透明)"""
    dx, dy = 200.0, 120.0
    ev = Ev()
    ev.session_start()
    ev.context(layer=1, color="#00aa00")
    ev.fill_v4(w // 2, h // 2, "#00aa00", tol=0)
    ev.transform(xtranslate=dx, ytranslate=dy)
    shifted = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    src = rgba_solid(w, h, (0, 170, 0, 255))
    shifted.paste(src, (int(dx), int(dy)))
    return "transform_translate", ev, shifted, 0.05


# 无快照用例: 从空白文档起步, 期望完全由事件流算出
CASES = {
    "fill_whole": case_fill_whole,
    "transform_translate": case_transform_translate,
    "gradient_linear": case_gradient,
}

# 带快照用例: 起点是一份自带内容的画面 (轮廓/色块), 期望 = 该内容 + 事件流的结果
SNAPSHOT_CASES = {
    "enclose_in_outline": case_enclose_in_outline,
    "fill_tolerance": case_fill_tolerance,
    "undo_fill": case_undo_fill,
    "redo_fill": case_redo_fill,
}


def adb(serial, *args, check=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, check=check)


def run_as(serial, *args):
    return adb(serial, "shell", "run-as", PKG, *args)


def push_and_install(serial, host, remote_name):
    tmp = "/data/local/tmp/%s" % remote_name
    adb(serial, "push", host, tmp, check=True)
    run_as(serial, "cp", tmp, "%s/projects/%s" % (FILES, remote_name))
    adb(serial, "shell", "rm", "-f", tmp)


def compare(replay_revp, expected_paint: Image.Image):
    with zipfile.ZipFile(replay_revp) as z:
        got = Image.open(io.BytesIO(z.read("layer_001.png"))).convert("RGBA")
    if got.size != expected_paint.size:
        return 1.0, {"error": "size mismatch", "got": got.size, "expected": expected_paint.size}
    diff = 0
    maxd = 0
    for (r1, g1, b1, a1), (r2, g2, b2, a2) in zip(got.getdata(), expected_paint.getdata()):
        d = max(abs(r1 - r2), abs(g1 - g2), abs(b1 - b2), abs(a1 - a2))
        if d > 8:
            diff += 1
            if d > maxd:
                maxd = d
    total = got.size[0] * got.size[1]
    return diff / total, {"diffPixels": diff, "total": total, "maxChannelDelta": maxd}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--only", default=None, help="只跑指定用例")
    ap.add_argument("--size", default="3392x2400",
                    help="必须与 --template 工程的画布尺寸一致")
    ap.add_argument("--template", default="/tmp/rp-perf/mytest.revp",
                    help="用作容器的真实工程 (只需结构合法, 内容会被替换)")
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()
    w, h = (int(v) for v in args.size.lower().split("x"))

    workdir = os.path.join(HERE, "..", ".replay-verify", "synth")
    workdir = os.path.abspath(workdir)
    os.makedirs(workdir, exist_ok=True)

    if not os.path.isfile(args.template):
        sys.exit(f"模板工程不存在: {args.template}")
    adb(args.serial, "shell", "am", "force-stop", PKG)
    adb(args.serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
    time.sleep(8)

    failures = []
    results = []
    plan = list(CASES.items()) + list(SNAPSHOT_CASES.items())
    for name, fn in plan:
        if args.only and name != args.only:
            continue
        if fn in SNAPSHOT_CASES.values():
            case, ev, expected, tol, snap = fn(w, h, args.template, workdir)
        else:
            case, ev, expected, tol = fn(w, h)
            snap = None
        revp = os.path.join(workdir, "%s.revp" % case)
        write_revp(args.template, revp, expected, build_recording(ev, w, h, snap))
        push_and_install(args.serial, revp, "%s.revp" % case)
        out_name = "synth-%s" % case
        print(f"\n=== {case} (容差 {tol:.1%}) ===")
        r = subprocess.run(
            [sys.executable, os.path.join(HERE, "replay-verify.py"),
             "--serial", args.serial or "", "--project", "%s.revp" % case,
             "--out", out_name, "--steps", "1.0", "--timeout", "120", "--no-launch", "--keep"],
            capture_output=True, text=True,
        )
        # replay-verify.py 自己会在拉取前清空该目录, 这里不要再删 (否则会把刚拉下来的产物删掉)
        host_out = os.path.join(HERE, "..", ".replay-verify", out_name)
        host_out = os.path.abspath(host_out)
        step = None
        if os.path.isdir(host_out):
            for f in os.listdir(host_out):
                if f.startswith("step_") and f.endswith(".revp"):
                    step = os.path.join(host_out, f)
        if not step:
            print(r.stdout[-1500:])
            print(r.stderr[-800:])
            failures.append((case, "no replay output"))
            continue
        ratio, info = compare(step, expected)
        ok = ratio <= tol
        print(f"  差异像素 {info.get('diffPixels')}/{info.get('total')} "
              f"= {ratio:.4%} (maxDelta {info.get('maxChannelDelta')}) -> "
              f"{'✅ 通过' if ok else '❌ 失败'}")
        results.append((case, ratio, info, ok))
        if not ok:
            failures.append((case, f"{ratio:.4%} > {tol:.1%}"))
        if not args.keep:
            run_as(args.serial, "rm", "-f", "%s/projects/%s.revp" % (FILES, case))

    print("\n---- 汇总 ----")
    for case, ratio, info, ok in results:
        print(f"{'✅' if ok else '❌'} {case:24s} 差异 {ratio:.4%}  "
              f"({info.get('diffPixels')} px)")
    if failures:
        print("\n失败:", failures)
        sys.exit(1)
    print("\n全部用例在容差内一致")


if __name__ == "__main__":
    main()
