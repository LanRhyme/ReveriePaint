<div align="center">

<img src="art/icon.svg" width="128" height="128" alt="ReveriePaint Icon" />

# ReveriePaint

[English](README_EN.md) | [简体中文](README.md)

<p>
  <a href="https://reveriepaint.lanrhyme.top"><img src="https://img.shields.io/badge/Website-reveriepaint.lanrhyme.top-5A6E8A?style=flat-square" alt="Website"></a>
  <a href="https://reveriepaint.lanrhyme.top/docs/"><img src="https://img.shields.io/badge/Docs-User%20Guide-7C8F9E?style=flat-square" alt="Docs"></a>
  <a href="https://github.com/LanRhyme/ReveriePaint/releases"><img src="https://img.shields.io/github/v/release/LanRhyme/ReveriePaint?color=5A6E8A&style=flat-square" alt="Release"></a>
  <a href="https://mirrorchyan.com/zh/projects?rid=ReveriePaint&os=android"><img src="https://img.shields.io/badge/MirrorChyan-Fast%20Download-5A6E8A?style=flat-square" alt="MirrorChyan"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2023%2B)-5A6E8A?style=flat-square" alt="Android Version">
  <img src="https://img.shields.io/badge/Arch-arm64--v8a-7C8F9E?style=flat-square" alt="Architecture">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-8D9E8F?style=flat-square" alt="License"></a>
  <a href="https://afdian.com/a/LanRhyme"><img src="https://img.shields.io/badge/Afdian-@LanRhyme-946ce6?style=flat-square" alt="Afdian"></a>
  <img src="https://img.shields.io/badge/QQ%20Group-729283213-12B7F5?style=flat-square" alt="QQ Group">
</p>

A native Android digital painting app powered by the Krita image engine and Jetpack Compose

</div>

---

## Official Navigation

- **Official Website**: [reveriepaint.lanrhyme.top](https://reveriepaint.lanrhyme.top)
- **Documentation**: [reveriepaint.lanrhyme.top/docs/](https://reveriepaint.lanrhyme.top/docs/)

---

## Architecture

ReveriePaint utilizes a hybrid architecture:

```
Kotlin / Jetpack Compose UI (Touch interactions and modern UI)
       │ JNI
C++ ReverieCore (Document management and rendering pipeline)
       │ C++
Krita Core Engine (KisImage / KisPainter / KisPaintOp)
```

- **UI Layer**: Jetpack Compose optimized for tablet touch gestures, supporting Material You dynamic colors and custom workspace themes
- **Core Layer**: C++ native engine wrapping Krita brush engines and compositing pipeline, executing rendering asynchronously off the main thread

---

## Key Capabilities

- **Krita Native Brush Pipeline**: Integrated Krita paintop engine with 240+ presets, custom brush studio, and pressure dynamic curves
- **Layer & Blending System**: Layer groups, filter layers, clipping masks, alpha lock, and 25 blend modes
- **Stylus & Touch Gestures**: Low-latency pressure adaptation, hover cursor preview, two-finger pan/zoom/rotate, and two-finger tap undo
- **Event-Stream Recording & Auto-Save**: Lightweight binary stroke recording with variable-speed time-lapse playback and background auto-save

For detailed technical guidelines, refer to the [User Guide](https://reveriepaint.lanrhyme.top/docs/), [AGENTS.md](AGENTS.md), and [CONTRIBUTING.md](CONTRIBUTING.md)

---

## Installation & Requirements

### System Requirements
- Operating System: Android 7.0 and above (API 23+)
- Architecture: 64-bit ARM only (`arm64-v8a`)
- Recommended: Android tablets with active pressure-sensitive stylus support

### Download Channels
- **Official Releases**: Download the latest APK from [GitHub Releases](https://github.com/LanRhyme/ReveriePaint/releases)
- **Mirror Source**: Fast download via [MirrorChyan](https://mirrorchyan.com/zh/projects?rid=ReveriePaint&os=android)

---

## Contributors

Thanks to everyone who contributed to ReveriePaint:

<a href="https://github.com/LanRhyme/ReveriePaint/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=LanRhyme/ReveriePaint" alt="Contributors" />
</a>

Check out [CONTRIBUTING.md](CONTRIBUTING.md) to learn how to get involved

---

## Sponsorship

ReveriePaint is maintained by independent creators and community contributors. If you enjoy using ReveriePaint, consider supporting development via Afdian:

- **Afdian**: [afdian.com/a/LanRhyme](https://afdian.com/a/LanRhyme)

---

## License & Credits

- **Application Source**: [GPL-3.0 License](LICENSE)
- **Image Engine**: [Krita](https://invent.kde.org/graphics/krita) (GPL-3.0)
- **Icon Assets**: [Tabler Icons](https://tabler.io/icons) (MIT)

---

## Community & Support

- **Official Website**: [reveriepaint.lanrhyme.top](https://reveriepaint.lanrhyme.top)
- **Documentation**: [reveriepaint.lanrhyme.top/docs/](https://reveriepaint.lanrhyme.top/docs/)
- **QQ Group**: 729283213
- **Issues & Suggestions**: Submit feedback on [GitHub Issues](https://github.com/LanRhyme/ReveriePaint/issues)
