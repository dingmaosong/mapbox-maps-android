import argparse
import os
from pathlib import Path
import shutil
import subprocess
from typing import Optional


def resolve_executable(arg_bin: Optional[str]) -> str:
    if arg_bin:
        p = Path(arg_bin)
        if p.exists():
            return str(p)
        return arg_bin

    env_bin = os.environ.get("BUILD_PBF_GLYPHS_BIN")
    if env_bin:
        p = Path(env_bin)
        if p.exists():
            return str(p)
        return env_bin

    home = Path.home()
    cargo_bin = home / ".cargo" / "bin" / "build_pbf_glyphs"
    if cargo_bin.exists():
        return str(cargo_bin)

    which = shutil.which("build_pbf_glyphs")
    if which:
        return which

    raise RuntimeError("未找到 build_pbf_glyphs，可先执行: cargo install build_pbf_glyphs --locked")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font-dir", default=None)
    parser.add_argument("--out", default=".")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--bin", default=None)
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    font_dir = Path(args.font_dir).resolve() if args.font_dir else script_dir
    out_dir = (script_dir / args.out).resolve() if not Path(args.out).is_absolute() else Path(args.out).resolve()

    if not font_dir.exists():
        raise RuntimeError(f"font-dir 不存在: {font_dir}")

    exe = resolve_executable(args.bin)
    cmd = [exe]
    if args.overwrite:
        cmd.append("--overwrite")
    cmd += [str(font_dir), str(out_dir)]

    subprocess.run(cmd, check=True)

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"❌ {e}")
        raise SystemExit(1)
