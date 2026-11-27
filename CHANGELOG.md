# ReveriePaint Changelog

[English](CHANGELOG.md) | [简体中文](CHANGELOG_ZH.md)

## [v1.11.1] - 2026-11-26

> **Warning**: Due to a major upgrade to the multi-user collab transport architecture, this release is incompatible with v1.11.0, all participants should update to v1.11.1 before joining the same room

### Features
- **Runtime Memory Monitor & Manual Cleanup**: Added a real-time memory card under Canvas Info in settings to inspect app and canvas memory usage, free reclaimable caches in one tap, and clear history with confirmation
- **Collab Transport Upgrade & Network Acceleration**: Upgraded the multi-user collab networking layer with dedicated relay support and an acceleration toggle to improve direct connection rates and sync stability

### Bug Fixes
- **Alpha Mask Stamp & Merge Fix**: Fixed black-and-white mask overlays when stamping visible layers, merging groups, picking colors, flood filling, magic wand selection, magnetic lasso selection, or exporting layers with transparency masks or stroke effects
- **Ink Brush Rotation & Nested Group Transform Fix**: Fixed fragmented strokes on rotated canvases when using the ink brush and fixed offset previews, artifacts, or undo mismatches when transforming nested layer groups
- **Find Edges Filter & Filter Layer Rasterization**: Fixed rendering glitches when choosing transparent line art mode in Find Edges, and changed filter layer rasterization to apply effects layer-by-layer to underlying layers
- **Brush Studio & Image Import Fixes**: Fixed color jitter behavior, test pad clearing residue, and studio brush import failures while unifying tip softness behavior; also fixed high-resolution image imports and cancel responsiveness
- **Collab Sync & Host Reconnect Fix**: Fixed undo mismatches, eraser/brush sync drift, stalled host handoff, and reconnect identity conflicts during collaborative sessions, with automatic room lease renewal and a host reclaim window
- **Inverse Selection Crash Guard**: Fixed a potential crash in selection preview when inverting selections on older Android versions

### Improvements
- **Runtime Memory Scheduling**: Tuned canvas cache budgets per device RAM tier and tightened automatic cache reclamation when switching projects, finishing filters, or moving to the background
- **Extended Localization Completion**: Completed Traditional Chinese, Japanese, and Cat language translations for runtime memory management, multi-user collab, and Brush Studio strings

## [v1.11.0] - 2026-11-26

### Features
- **Multi-User Collab & Online Room Lobby (Beta)**: Join the same canvas in real time via a 6-character room code or public room list, with exclusive/public/locked layer permissions, join approval, password protection, host reconnect takeover, and collab project markers (by @LanRhyme in #236)

### Bug Fixes
- **High Refresh Rate Latency & Front-Buffer Resume**: Fixed canvas overlay deoptimization causing input lag on high-refresh tablets, plus missing predicted ink or extra redraws when resuming from background or falling back to software rendering
- **High-Density Imported Brush Stutter Mitigation**: Added safe spacing floors for tight non-scattered tip presets and dual brushes to reduce stutter during continuous heavy strokes

### Improvements
- **Latency & Stylus Diagnostics Report**: Added per-brand stylus detection summary, display refresh rate, scheduling/front-buffer status, and recent stroke latency telemetry to diagnostics logs
- **Extended Localization Completion**: Completed Traditional Chinese, Japanese, and Cat language translations for multi-user collab and the online room lobby

## [v1.10.3] - 2026-11-25

### Features
- **Brush Studio Redesign**: Reorganized Brush Studio categories to match the overall visual style and added a full blend-mode search panel, Wash/Build-Up paint modes, and an independent eraser switch
- **Color Dynamics & Stroke Noise**: Added hue, saturation, and brightness dynamics curves along with stroke noise controls
- **Speed-Simulated Pressure & Named Undo Toasts**: Added stroke-speed-based width simulation (configurable for finger/non-pressure styli only) and descriptive action names on undo/redo toasts
- **Color Picker Comparison Bubble**: Displays a split comparison bubble of the previous and selected colors while dragging on the wheel or square picker

### Bug Fixes
- **Textured Brush Degradation Fix**: Fixed texture-tip presets occasionally falling back to plain round tips after lazy loading with broken reset behavior
- **Tablet Canvas & Multithreading Stability**: Fixed occasional crashes on Android 12 and 13 tablets when entering or switching canvases and hardened multi-core brush rendering and transform math guards

### Improvements
- **Liquify Smoothness & Parameter Persistence**: Improved real-time liquify fluidity, kept cursor outline sizing consistent, and remembered the last used liquify settings
- **Extended Localization Completion**: Completed Traditional Chinese, Japanese, and Cat language translations for new Brush Studio, blend mode, and settings strings

## [v1.10.2] - 2026-11-23

### Features
- **Quick Layers Drag Reorder**: Long-press and drag layers to reorder them directly inside the Quick Layers panel
- **Layer Swipe-to-Clear & Back Navigation**: Improved left-swipe clearing and property detail back navigation in both Layers and Quick Layers panels
- **Cross-Version Changelog Aggregation**: Update dialog aggregates all release notes between your installed version and the latest release
- **Canvas Open Wave Effect**: Added an organic wave transition toggle in theme settings with layered lighting transitions
- **Fluid Spring Interactions**: Unified spring physics and haptic feedback across segmented controls, switches, and sliders

### Bug Fixes
- **PSD Layer Mask Import**: Fixed missing PSD layer masks by automatically converting them into transparency masks on import
- **Brush & Canvas Stability**: Fixed crashes triggered by bristle brushes, texture options, canvas switching, and resource loading

### Improvements
- **Layer List Visual Polish**: Refined selected layer backgrounds, drag-floating cards, and insertion indicator lines

## [v1.10.1] - 2026-11-22

### Features
- **Transform Box Lightweight Visuals**: Kept only the four corner badges with direct edge-line scaling and switched handles/outlines to translucent light gray to reduce visual obstruction
- **5-Point Fisheye Perspective Guide & Snapping**: Added 5-point fisheye spherical grids and center rays with curve snapping and grouped them under Perspective guides
- **Split HSV & HSL Adjustments**: Separated the legacy HSBC filter into dedicated HSV and HSL adjustment filters with smoother additive HSL lightness
- **Independent Reference Window Pages**: Image and canvas tabs in the Reference window maintain independent zoom and pan transforms
- **Selection Copy-Only & Storyboard Improvements**: Added copy-only/paste actions to selection menu, preserved timeline toggle access, and added SRT storyboard frame & multi-audio track support (by @Hidden-Jade in #188)

### Bug Fixes
- **Timelapse Missing Frames & Black Background**: Used bilinear downsampling with relaxed frame-change thresholds, removed duplicate throttling, and fixed black backgrounds when exporting transparent MP4s
- **Save Failure Diagnostics & Low-Memory Protection**: Validated zip and PNG outputs with structured stage/errno error reporting, persistent failure logs, pre-save cache trimming, low-memory alerts, and empty-layer snapshot guards
- **Transparency Mask & KRA Compatibility**: Fixed transparency masks sticking to unrelated groups or tearing on drag, and added full Krita native `pixelselection` mask import/export
- **Comic Panel & Animation Cel Fixes**: Fixed panel mask/border metadata syncing, top-level border re-stroking on export, new animation frame cel occupancy, and single-cel clearing

### Improvements
- **Timelapse Frame Capture Reuse**: Reused a single capture bitmap per stroke to eliminate repeated 1080p allocations
- **Cold-Start Preset Fast Check & Log Throttling**: Checked preset `mtime + size` to skip redundant disk writes on startup and filtered noisy logs

## [v1.10.0] - 2026-11-21

### Features
- **Multi-Block Vector Text Layers**: Maintain multiple text blocks per layer with inline live editing, horizontal/vertical layouts, boundary auto-wrap, and full project persistence
- **QuickShape Symmetry & 3D Perspective Integration**: Enabled QuickShape recognition alongside Symmetry and 3D Perspective guides with real-time mirrored previews (by @MatikaneMeika in #205)
- **Shortcut Gestures & Three-Finger Brush Swipe**: Added double-tap and tap-drag gesture bindings for canvas flip, reset rotation, eraser toggle, plus three-finger horizontal/vertical swipes for brush size and opacity
- **Free Canvas Adjust Gestures**: Pinch, drag, and rotate freely while using the canvas crop/adjust tool with continuous live feedback
- **Motion & Lighting Polish**: Added gallery-to-canvas wave transitions, staggered panel entrances, selection glow bursts, eyedropper spectrum glow, and shimmer toasts

### Bug Fixes
- **Timelapse & Mask Capture Fixes**: Fixed white-block artifacts from transparency mask frame capture and updated export video dimensions after resizing the canvas
- **Shading Assist OOM & Lifecycle Guards**: Added downsampling safeguards on huge canvases and fixed bitmap lifecycle crashes
- **Transform Undo Drift & Per-Gesture Undo**: Fixed async guard timing and added per-gesture undo within a transform session (by @MatikaneMeika in #204)
- **Drawing Guide Handle Convergence**: Tightened Symmetry and Perspective handle hit radii and split Symmetry Center X/Y sliders (by @MatikaneMeika in #203)
- **Compose Lint Fixes**: Resolved direct `Context` resource lookups inside `CanvasAdjustOverlay`

### Improvements
- **Selection Viewport Clipping & Optimization**: Inverse-viewport clipped selection overlays and marching ants to eliminate lag when zooming deep into selections
- **Drawing Assist Panel Redesign**: Rebuilt Drawing Assist into Spatial Guides and QuickShape tabs with one-tap highlight disable pills (by @MatikaneMeika in #202)
- **Dual-Island Bottom Navigation**: Reworked the home bottom bar into a dual-island capsule dock with frosted glass and haptic slot snapping

---

> Older releases (`v1.9.3` and earlier) are archived in Chinese in [CHANGELOG_ZH.md](CHANGELOG_ZH.md)
