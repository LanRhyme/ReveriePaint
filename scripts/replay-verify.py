#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""录制/回放保真度自动验证 (ReveriePaint).

思路: 回放就是把录制的事件流重新灌回同一个引擎, 所以"把带录制的工程回放到某个进度,
再把文档另存出来, 与工程本身逐层像素比较"就是回放是否忠实的**客观判据**。
命令行看屏幕截图既慢又主观, 复杂作品更没法确认; 这里是逐像素的。

依赖 debug 包里的 ReplayVerifyReceiver (app/src/debug/.../ReplayVerifyReceiver.kt)。

用法:
  scripts/replay-verify.py --project "未命名作品 6.revp" --steps 0.5,1.0
  scripts/replay-verify.py --project xxx.revp --out run2 --keep
"""

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import time
import zipfile

from PIL import Image

PKG = "com.reverie.paint"
ACTION = "com.reverie.paint.DEBUG_REPLAY_VERIFY"
RECEIVER = "com.reverie.paint.perf.ReplayVerifyReceiver"
FILES = "/data/data/%s/files" % PKG


def adb(serial, *args, check=True, capture=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, check=False)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.decode('utf-8', 'replace')}")
    return r.stdout if capture else r


def run_as(serial, *args):
    """在应用私有目录里执行命令 (debug 包, 可 run-as)."""
    return adb(serial, "shell", "run-as", PKG, *args)


def read_file(serial, path):
    r = subprocess.run(["adb"] + (["-s", serial] if serial else []) +
                       ["shell", f"run-as {PKG} cat '{path}'"], capture_output=True)
    return r.stdout if r.returncode == 0 else b""


def write_host_file(serial, host_path, device_path):
    """把宿主机文件写进应用私有目录 (先推到 /data/local/tmp 再 cp)."""
    tmp = "/data/local/tmp/rv_%d" % os.getpid()
    adb(serial, "push", host_path, tmp)
    run_as(serial, "cp", tmp, device_path)
    adb(serial, "shell", "rm", "-f", tmp)


def pull_dir(serial, device_dir, host_dir):
    # 同一 --out 名重复跑时, 先清空宿主机目录: 否则拉取失败会静默复用上一次的产物
    # (曾经因此把"旧用例的图"当成新结果, 误判成回放 bug)
    if os.path.isdir(host_dir):
        shutil.rmtree(host_dir, ignore_errors=True)
    os.makedirs(host_dir, exist_ok=True)
    listing = run_as(serial, "ls", device_dir).decode("utf-8", "replace").split()
    for name in listing:
        if not name or name.endswith("/"):
            continue
        blob = read_file(serial, f"{device_dir}/{name}")
        if blob:
            with open(os.path.join(host_dir, name), "wb") as f:
                f.write(blob)


def layer_pngs(revp_path):
    """从 .revp 里取出 layer_*.png 像素 (bytes)."""
    out = {}
    with zipfile.ZipFile(revp_path) as z:
        for name in z.namelist():
            if name.startswith("layer_") and name.endswith(".png"):
                out[name] = z.read(name)
    return out


def ink_pixels(revp_path):
    """每个图层的非透明像素数 (看回放到底画出了多少内容)."""
    try:
        from PIL import Image
        import io
    except ImportError:
        return {}
    out = {}
    with zipfile.ZipFile(revp_path) as z:
        for name in z.namelist():
            if not (name.startswith("layer_") and name.endswith(".png")):
                continue
            im = Image.open(io.BytesIO(z.read(name))).convert("RGBA")
            alpha = im.split()[3]
            out[name] = sum(1 for v in alpha.getdata() if v > 8)
    return out


def compare_pngs(a_path, b_path):
    """逐层比较两个 .revp 的图层像素, 返回 (总体一致?, 每层差异报告)."""
    try:
        from PIL import Image, ImageChops
        import io
    except ImportError:
        Image = None
    a = layer_pngs(a_path)
    b = layer_pngs(b_path)
    report = {"onlyInA": sorted(set(a) - set(b)), "onlyInB": sorted(set(b) - set(a)), "layers": []}
    same = not report["onlyInA"] and not report["onlyInB"]
    for name in sorted(set(a) & set(b)):
        if a[name] == b[name]:
            report["layers"].append({"layer": name, "identical": True})
            continue
        same = False
        entry = {"layer": name, "identical": False, "bytesA": len(a[name]), "bytesB": len(b[name])}
        if Image is not None:
            try:
                ia = Image.open(io.BytesIO(a[name])).convert("RGBA")
                ib = Image.open(io.BytesIO(b[name])).convert("RGBA")
                if ia.size != ib.size:
                    entry["sizeMismatch"] = [list(ia.size), list(ib.size)]
                else:
                    diff = ImageChops.difference(ia, ib)
                    bbox = diff.getbbox()
                    if bbox:
                        # 统计差异像素数与最大通道差
                        dpix = sum(1 for px in diff.getdata() if px != (0, 0, 0, 0))
                        mx = max(max(px) for px in diff.getdata())
                        entry["diffBBox"] = list(bbox)
                        entry["diffPixels"] = dpix
                        entry["maxChannelDelta"] = mx
                        entry["totalPixels"] = ia.size[0] * ia.size[1]
                        entry["diffRatio"] = round(dpix / (ia.size[0] * ia.size[1]), 6)
            except Exception as e:  # noqa: BLE001 - 诊断脚本, 失败也继续
                entry["imageError"] = str(e)
        report["layers"].append(entry)
    return same, report


def _ink_ratio(img):
    """非白像素占比 (白底画布上的"内容量")"""
    g = img.convert("RGB")
    total = g.size[0] * g.size[1]
    if total == 0:
        return 0.0
    ink = 0
    for r, gg, b in g.getdata():
        if r < 240 or gg < 240 or b < 240:
            ink += 1
    return ink / total


def _layer_meta(z):
    """从 layers.xml 读每层的 filename / 可见性 / 不透明度 (合成时必须尊重, 否则会高估文档墨迹)"""
    import re
    try:
        xml = z.read("layers.xml").decode("utf-8", "replace")
    except KeyError:
        return {}
    out = {}
    for tag in re.findall(r"<[a-z]*layer\b[^>]*>", xml):
        fn = re.search(r'filename="([^"]+)"', tag)
        if not fn:
            continue
        vis = re.search(r'visible="(\d+)"', tag)
        opa = re.search(r'opacity="(\d+)"', tag)
        out[fn.group(1)] = (
            (vis.group(1) != "0") if vis else True,
            (int(opa.group(1)) / 255.0) if opa else 1.0,
        )
    return out


def _merged_doc(step_path, size):
    """把某进度另存出来的文档合成成"白底 + 各可见层"的画面 (缩到尾帧尺寸)"""
    with zipfile.ZipFile(step_path) as z:
        meta = _layer_meta(z)
        names = [n for n in z.namelist() if n.startswith("layer_") and n.endswith(".png")]
        merged = Image.new("RGBA", size, (255, 255, 255, 255))
        for n in names:
            visible, opacity = meta.get(n, (True, 1.0))
            if not visible or opacity <= 0.0:
                continue
            lay = Image.open(io.BytesIO(z.read(n))).convert("RGBA")
            if opacity < 1.0:
                alpha = lay.split()[3].point(lambda v: int(v * opacity))
                lay.putalpha(alpha)
            if lay.size != size:
                lay = lay.resize(size, Image.NEAREST)
            merged = Image.alpha_composite(merged, lay)
        return merged


def check_frames(host_out, result):
    """上屏帧墨迹 vs 同进度文档墨迹: 明显偏少说明投影没整幅合成"""
    frames = sorted(f for f in os.listdir(host_out)
                    if f.startswith("frame_") and f.endswith(".png"))
    if not frames:
        return []
    steps = sorted(
        (s for s in result.get("steps", []) if s.get("file")),
        key=lambda s: float(s.get("fraction", 0)),
    )
    print(f"      上屏帧 {len(frames)} 张 (与同进度文档对照)")
    warnings = []
    # 帧文件名里带进度: frame_00_0.300.png / frame_00_0.300_late.png —— 按进度配文档,
    # 不能按顺序配 (一张进度可能有多帧: 早帧 + 晚帧)
    def frac_of(name):
        try:
            base = name.replace("_late.png", ".png")  # 晚帧后缀先去掉, 否则 split 出来是 "late"
            return float(base.split("_")[-1].replace(".png", ""))
        except ValueError:
            return None

    for fname in frames:
        frame = Image.open(os.path.join(host_out, fname))
        ratio = None
        doc_ink = None
        want = frac_of(fname)
        idx = next((i for i, st in enumerate(steps)
                    if want is not None and abs(float(st.get("fraction", 0)) - want) < 1e-3), None)
        if idx is not None:
            step_path = os.path.join(host_out, steps[idx]["file"])
            if os.path.isfile(step_path):
                try:
                    doc_ink = _ink_ratio(_merged_doc(step_path, frame.size))
                    frame_ink = _ink_ratio(frame)
                    ratio = (frame_ink / doc_ink) if doc_ink > 0 else 1.0
                except Exception:
                    ratio = None
        if ratio is None:
            print(f"        {fname}: 无对照")
            continue
        bad = doc_ink > 0.01 and ratio < 0.6
        print(f"        {fname}: 帧 {ratio:.2f}x 文档 {'<!-- 帧明显偏少 -->' if bad else ''}{'❌' if bad else '✅'}")
        if bad:
            warnings.append(fname)
    return warnings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None, help="adb 设备序列号 (多设备时必填)")
    ap.add_argument("--project", required=True, help="工程文件名 (位于 files/projects/) 或绝对路径")
    ap.add_argument("--out", default=None, help="输出子目录名 (默认按时间戳)")
    ap.add_argument("--steps", default="0.5,1.0", help="要验证的进度点, 逗号分隔")
    ap.add_argument("--timeout", type=int, default=900, help="整体超时 (秒)")
    ap.add_argument("--keep", action="store_true", help="保留设备侧产物")
    ap.add_argument("--no-launch", action="store_true", help="不重新拉起应用")
    args = ap.parse_args()

    serial = args.serial
    run_id = args.out or time.strftime("run%Y%m%d_%H%M%S")
    device_out = f"{FILES}/verify/{run_id}"
    host_out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".replay-verify", run_id)
    host_out = os.path.abspath(host_out)

    project = args.project
    if not project.startswith("/"):
        project = f"{FILES}/projects/{project}"
    if not read_file(serial, project):
        sys.exit(f"工程读不到: {project}")

    if not args.no_launch:
        adb(serial, "shell", "am", "force-stop", PKG)
        adb(serial, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        time.sleep(8)

    print(f"[1/4] 触发回放验证: {os.path.basename(project)} steps={args.steps}")
    # 注意: adb shell 会把参数拼成一条命令再交给设备 shell, 路径里的空格/中文必须自己带引号
    # 必须显式指定组件 + --receiver-include-background: Android 8 以后隐式广播到清单
    # 接收器会被后台限制拦掉, 只有显式组件才稳定送达
    cmd = (f"am broadcast -n {PKG}/{RECEIVER} -a {ACTION} --receiver-include-background "
           f"--es path '{project}' --es out '{device_out}' --es steps '{args.steps}'")
    adb(serial, "shell", cmd, check=False)

    print(f"[2/4] 等待 result.json (超时 {args.timeout}s) ...")
    t0 = time.time()
    result = None
    while time.time() - t0 < args.timeout:
        raw = read_file(serial, f"{device_out}/result.json")
        if raw:
            try:
                result = json.loads(raw.decode("utf-8", "replace"))
                break
            except json.JSONDecodeError:
                pass
        time.sleep(5)
    if result is None:
        sys.exit("超时: 没等到 result.json (看 logcat -s RP-REPLAYVERIFY 排查)")

    print(f"[3/4] 结果: ok={result.get('ok')} events={result.get('events')} "
          f"totalMs={result.get('totalMs')} loadMs={result.get('loadMs')} "
          f"replayOpenMs={result.get('replayOpenMs')}")
    for st in result.get("steps", []):
        print(f"      step f={st.get('fraction')} seek={st.get('seekMs')}ms "
              f"save={st.get('saveMs')}ms bytes={st.get('bytes')} saved={st.get('saved')}")
    if not result.get("ok"):
        sys.exit(f"验证失败: {result.get('error')}")

    print("[4/4] 拉取产物并逐层比较像素")
    pull_dir(serial, device_out, host_out)
    # 上屏帧自检: 文档对不代表用户看到的画面对 —— seek 后若投影没整幅重合成,
    # 画布上只剩当前脏区 ("显示不出来 / 只剩一条"), 而另存的文档本身是好的。
    frame_warnings = check_frames(host_out, result)
    final = [s for s in result.get("steps", []) if abs(float(s.get("fraction", 0)) - 1.0) < 1e-6]
    if not final:
        print("没有 1.0 这一步, 跳过终态比较 (要看终态就用 --steps 1.0 至少跑一次)")
        if frame_warnings:
            print(f"上屏帧异常 {len(frame_warnings)} 张: {', '.join(frame_warnings)}")
        sys.exit(0 if not frame_warnings else 1)
    replay_file = os.path.join(host_out, final[0]["file"])
    original_file = os.path.join(host_out, "original.revp")
    with open(original_file, "wb") as f:
        f.write(read_file(serial, project))
    if not os.path.isfile(replay_file):
        sys.exit(f"回放产物没拉下来: {replay_file}")

    same, report = compare_pngs(original_file, replay_file)
    with open(os.path.join(host_out, "compare.json"), "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    # 回放产物各自画了多少墨 (绝对值, 与原件比不上的时候很有诊断价值)
    for st in result.get("steps", []):
        step_file = os.path.join(host_out, st.get("file", ""))
        if os.path.isfile(step_file):
            print(f"      步骤 f={st.get('fraction')} 墨迹像素: {ink_pixels(step_file)}")
    print(f"      原件墨迹像素: {ink_pixels(original_file)}")
    bad = [l for l in report["layers"] if not l.get("identical")]
    print(f"      图层: {len(report['layers'])} 个, 不一致 {len(bad)} 个")
    for l in bad[:12]:
        print(f"        - {l['layer']}: diffPixels={l.get('diffPixels')} "
              f"ratio={l.get('diffRatio')} bbox={l.get('diffBBox')} "
              f"maxDelta={l.get('maxChannelDelta')}")
    if report["onlyInA"] or report["onlyInB"]:
        print(f"      只在原件: {report['onlyInA']} 只在回放: {report['onlyInB']}")

    print()
    if frame_warnings:
        print(f"上屏帧异常 {len(frame_warnings)} 张: {', '.join(frame_warnings)}")
    ok = same and not frame_warnings
    print("结论:", "✅ 回放与原件逐层一致" if ok else "❌ 回放与原件不一致")
    print(f"产物: {host_out}")
    if not args.keep and same:
        adb(serial, "shell", "run-as", PKG, "rm", "-rf", device_out, check=False)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
