## 变更概述 (Summary)

<!-- 说明本次 PR 解决的问题或引入的新能力，按常规格式关联 Issue 或 RFC 提案 -->
Fixes #
RFC Issue #

## 变更类型 (Type of Change)

- [ ] `feat`: 新增功能 (Feature)
- [ ] `fix`: 缺陷修复 (Bug Fix)
- [ ] `perf`: 性能优化 (Performance Optimization)
- [ ] `refactor`: 代码重构 (Refactoring without logic change)
- [ ] `docs`: 文档或多语言文案更新 (Documentation / Strings)
- [ ] `build` / `chore`: 构建系统或工程配置调整 (Build / Maintenance)

## 贡献合规与 AI 辅助声明 (Compliance & AI Declaration)

- [ ] 变更代码是否包含 AI (如 ChatGPT / Claude / Gemini / Copilot / Cursor) 生成或辅助编写的内容？
  - 若包含，请勾选并在下方简要注明辅助范围与模型：
  - [ ] 本人已逐行审阅并完全理解 AI 生成的代码，确认其符合项目架构要求且已实机自测
  - 使用的模型与辅助范围说明: 

## 架构与铁律自检 (Architecture Checklist)

修改核心或渲染代码时，请确认未违反 AGENTS.md 架构铁律与设计边界:

- [ ] **依赖单向**: 遵循 `ui -> core -> model` 单向依赖，未反向引用
- [ ] **线程模型**: 文档与渲染操作未进入 UI 线程，均由 Handler 异步调度
- [ ] **双缓冲机制**: 写像素线程与 Compose 读取的 Bitmap 维持前后双缓冲隔离
- [ ] **热路径零分配**: 笔触采集、渲染分发、录制编解码热路径上未引入逐帧临时对象与字符串拼接
- [ ] **手势隔离**: `pointerInput` 探测器未以 zoom/pan/rotation 为 key，手势不会第一帧中断
- [ ] **文案国际化**: 新增或修改文案已使用 `strings.xml` 管理，并同步提供中英文翻译 (`values/` 与 `values-en/`)
- [ ] **C++ 规范**: C++ 文件附带有效 SPDX 许可声明，未引入 QWidget 依赖

## 验证与测试 (Verification & Testing)

请勾选已执行的自测流程并注明测试设备:

- [ ] 架构守则检查通过 (`./scripts/check_architecture.sh`)
- [ ] 机械编译自检通过 (`./gradlew :app:compileDebugKotlin`)
- [ ] JVM 单元测试通过 (`./gradlew :app:testDebugUnitTest`)
- [ ] Android Lint 检查通过 (`./gradlew :app:lintDebug`)
- [ ] 真机验证通过 (请填写测试机型与手写笔硬件型号: ________)
