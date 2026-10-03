"""Extract production kernels and their pre-shortcut references for host regression."""
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
build = root / "build"
build.mkdir(exist_ok=True)
cpp = (root / "app/src/main/cpp/LiquifyInverseField.h").read_text(encoding="utf-8")
cpp = re.sub(r'                if \(t == 0\.f\) \{.*?\n                \}', '', cpp, flags=re.S)
(build / "liquify_kernel_reference.h").write_text(
    cpp.replace("class LiquifyInverseField", "class LiquifyKernelReference"), encoding="utf-8")
kt = (root / "app/src/main/java/com/reverie/paint/ui/painting/canvas/LiquifyGlesOverlay.kt").read_text(encoding="utf-8")
shader = re.search(r'val DAB_FS\s*=\s*"""(.*?)"""', kt, re.S).group(1)
reference = re.sub(r'                if \(t == 0\.0\) \{.*?\n                \}', '', shader, flags=re.S)
(build / "liquify_dab_shader.h").write_text(
    'const char* productionDab = R"GLSL(' + shader + ')GLSL";\n'
    'const char* referenceDab = R"GLSL(' + reference + ')GLSL";\n', encoding="utf-8")
