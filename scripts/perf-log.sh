#!/usr/bin/env bash
# 收集 ReveriePaint 性能埋点日志 (PerfTrace)
#
# 用法:
#   ./scripts/perf-log.sh              # 清缓冲并持续跟踪
#   ./scripts/perf-log.sh --dump       # 只导出当前缓冲
#   ./scripts/perf-log.sh --samples 20 # 持续跟踪 (默认 20 行后由用户 Ctrl-C)
#
# 埋点说明 (TAG=ReveriePerf):
#   timeline.draw    时间轴 Canvas 单帧绘制耗时 (>= slowMs 才打印)
#   timeline.blocks  时间轴每秒绘制的"帧块"次数 (窗口 2000ms 聚合)
#   timeline.images  时间轴每秒绘制的缩略图次数 (窗口 2000ms 聚合)
#   render.buffer    引擎 renderToBuffer 耗时 (>= 8ms 才打印)
#   render.calls     引擎每秒渲染次数 (窗口 1000ms 聚合)
#   thumbs.refreshCalls 每秒发起缩略图重建次数 (窗口 1000ms; 手势期间应被 180ms 防抖压住)

set -u

SERIAL="${REVERIE_SERIAL:-c84b9192}"
MODE="${1:-follow}"

if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
  echo "✗ 设备 $SERIAL 未连接 (可通过 REVERIE_SERIAL=xxx 覆盖)" >&2
  exit 1
fi

case "$MODE" in
  --dump)
    echo "=== 当前 ReveriePerf 缓冲 (设备 $SERIAL) ==="
    adb -s "$SERIAL" logcat -d -s ReveriePerf
    ;;
  *)
    echo "=== 清空缓冲, 开始跟踪 ReveriePerf (设备 $SERIAL) ==="
    echo "请在平板上: 进入一个项目 → 打开时间轴 → 绘制 + 上下滑动/双指缩放"
    echo "按 Ctrl-C 结束。"
    adb -s "$SERIAL" logcat -c
    exec adb -s "$SERIAL" logcat -s ReveriePerf
    ;;
esac
