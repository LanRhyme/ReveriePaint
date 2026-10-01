from pathlib import Path
import re
root = Path(__file__).resolve().parent.parent
source = (root / "app/src/main/java/com/reverie/paint/ui/painting/canvas/LiquifyGlesOverlay.kt").read_text(encoding="utf-8")
shader = re.search(r'val FS\s*=\s*"""(.*?)"""', source, re.S).group(1)
(root / "build").mkdir(exist_ok=True)
(root / "build/liquify_scene_shader.h").write_text('const char* productionFS = R"GLSL(' + shader + ')GLSL";', encoding="utf-8")
