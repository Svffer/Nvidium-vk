"""Offline check of nvidium's Vulkan shaders with glslc.

Resolves `#moj_import <ns:path>` the way ShaderLoader does (assets/<ns>/shaders/<path>), injects
the defines ShaderLoader would add for a given configuration, and compiles every entry point under
assets/nvidium/shaders/vk with glslc for Vulkan 1.2 / SPIR-V 1.5. Exit status 1 if anything fails.

Usage: python3 -I bench/compile-vk-shaders.py [-D DEFINE ...] [--keep DIR] [--sodium JAR]
Default defines mirror NvidiumConfig defaults: RENDER_FOG, CULL_DEGENERATE_TRIANGLES,
TRANSLUCENCY_SORTING_SODIUM, USE_NV_FRAGMENT_SHADER_BARYCENTRIC, TEXTURE_MAX_SCALE.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets")
VK_DIR = os.path.join(ASSETS, "nvidium", "shaders", "vk")

STAGES = {".task": "task", ".mesh": "mesh", ".frag": "frag", ".comp": "comp"}
DEFAULT_DEFINES = [
    "RENDER_FOG",
    "CULL_DEGENERATE_TRIANGLES",
    "TRANSLUCENCY_SORTING_SODIUM",
    "USE_NV_FRAGMENT_SHADER_BARYCENTRIC",
    "TEXTURE_MAX_SCALE=32768",
]
IMPORT_RE = re.compile(r"^\s*#moj_import\s*<([a-z0-9_]+):([^>]+)>\s*$", re.M)


def find_sodium_jar(explicit):
    if explicit:
        return explicit
    jars = glob.glob(os.path.join(ROOT, "build", "sodium", "*.jar"))
    return jars[0] if jars else None


def resolve(ns, path, sodium_jar):
    if ns == "nvidium":
        with open(os.path.join(ASSETS, "nvidium", "shaders", path), encoding="utf-8") as f:
            return f.read()
    if ns == "sodium" and sodium_jar:
        with zipfile.ZipFile(sodium_jar) as z:
            return z.read("assets/sodium/shaders/" + path).decode("utf-8")
    raise FileNotFoundError(f"{ns}:{path}")


def preprocess(source, sodium_jar, depth=0):
    if depth > 16:
        raise RecursionError("import depth")

    def sub(m):
        return preprocess(resolve(m.group(1), m.group(2), sodium_jar), sodium_jar, depth + 1)

    return IMPORT_RE.sub(sub, source)


def inject_defines(source, defines):
    # Like GlslPreprocessor.injectDefines: after the #version line.
    lines = source.split("\n")
    out = []
    injected = False
    for line in lines:
        out.append(line)
        if not injected and line.strip().startswith("#version"):
            for d in defines:
                name, _, value = d.partition("=")
                out.append(f"#define {name} {value}".rstrip())
            injected = True
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-D", dest="defines", action="append", default=None)
    ap.add_argument("--keep", help="directory to keep the preprocessed sources and SPIR-V in")
    ap.add_argument("--sodium", help="Sodium mod jar for sodium: imports")
    args = ap.parse_args()
    defines = args.defines if args.defines is not None else DEFAULT_DEFINES
    sodium_jar = find_sodium_jar(args.sodium)

    entries = sorted(
        p for p in glob.glob(os.path.join(VK_DIR, "**", "*"), recursive=True) if os.path.splitext(p)[1] in STAGES
    )
    if not entries:
        print(f"no shader entry points under {VK_DIR}", file=sys.stderr)
        return 1

    outdir = args.keep or tempfile.mkdtemp(prefix="nvidium-vk-shaders-")
    os.makedirs(outdir, exist_ok=True)
    failed = 0
    for path in entries:
        rel = os.path.relpath(path, VK_DIR)
        stage = STAGES[os.path.splitext(path)[1]]
        with open(path, encoding="utf-8") as f:
            src = inject_defines(preprocess(f.read(), sodium_jar), defines)
        pre = os.path.join(outdir, rel.replace(os.sep, "__") + ".glsl")
        spv = pre[:-5] + ".spv"
        with open(pre, "w", encoding="utf-8") as f:
            f.write(src)
        cmd = [
            "glslc",
            f"-fshader-stage={stage}",
            "--target-env=vulkan1.2",
            "--target-spv=spv1.5",
            "-O",
            "-o",
            spv,
            pre,
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        status = "ok  " if res.returncode == 0 else "FAIL"
        print(f"{status} {rel}")
        if res.returncode != 0:
            failed += 1
            # Map glslc's line numbers back to the preprocessed file we kept.
            print(res.stderr.replace(pre, f"  [{rel} preprocessed: {pre}]").rstrip())
        elif res.stderr.strip():
            print(res.stderr.rstrip())
    print(f"{len(entries) - failed}/{len(entries)} compiled; defines: {' '.join(defines)}; output: {outdir}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
