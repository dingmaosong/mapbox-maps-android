import argparse
import os
from pathlib import Path
import shutil
import subprocess
from typing import Optional
import json
from typing import Any, Dict, Iterable, List, Set, Tuple


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


def _iter_feature_strings(feature: Dict[str, Any], key: str) -> Iterable[str]:
    props = feature.get("properties") or {}
    if not isinstance(props, dict):
        return []
    v = props.get(key)
    if isinstance(v, str):
        return [v]
    return []


def _layer_text_field_key(layer: Dict[str, Any]) -> Optional[str]:
    layout = layer.get("layout") or {}
    if not isinstance(layout, dict):
        return None
    text_field = layout.get("text-field")
    if isinstance(text_field, str):
        return text_field
    if isinstance(text_field, list) and len(text_field) == 2 and text_field[0] == "get" and isinstance(text_field[1], str):
        return text_field[1]
    return None


def _layer_text_fonts(layer: Dict[str, Any]) -> List[str]:
    layout = layer.get("layout") or {}
    if not isinstance(layout, dict):
        return []
    fonts = layout.get("text-font")
    if isinstance(fonts, str):
        return [fonts]
    if isinstance(fonts, list):
        return [f for f in fonts if isinstance(f, str)]
    return []


def _matches_in_get_filter(feature: Dict[str, Any], filter_value: Any) -> Optional[bool]:
    if not isinstance(filter_value, list) or len(filter_value) < 3:
        return None
    if filter_value[0] != "in":
        return None
    get_expr = filter_value[1]
    if not (isinstance(get_expr, list) and len(get_expr) == 2 and get_expr[0] == "get" and isinstance(get_expr[1], str)):
        return None
    key = get_expr[1]
    props = feature.get("properties") or {}
    if not isinstance(props, dict):
        return False
    v = props.get(key)
    return v in filter_value[2:]


def _matches_eq_get_filter(feature: Dict[str, Any], filter_value: Any) -> Optional[bool]:
    if not isinstance(filter_value, list) or len(filter_value) != 3:
        return None
    if filter_value[0] != "==":
        return None
    get_expr = filter_value[1]
    if not (isinstance(get_expr, list) and len(get_expr) == 2 and get_expr[0] == "get" and isinstance(get_expr[1], str)):
        return None
    key = get_expr[1]
    expected = filter_value[2]
    props = feature.get("properties") or {}
    if not isinstance(props, dict):
        return False
    return props.get(key) == expected


def _filter_features_for_layer(features: List[Dict[str, Any]], layer: Dict[str, Any]) -> List[Dict[str, Any]]:
    filter_value = layer.get("filter")
    if filter_value is None:
        return features

    out: List[Dict[str, Any]] = []
    for f in features:
        m = _matches_eq_get_filter(f, filter_value)
        if m is None:
            m = _matches_in_get_filter(f, filter_value)
        if m is None:
            return features
        if m:
            out.append(f)
    return out


def _collect_required_ranges_from_style(style_path: Path) -> Dict[str, Set[str]]:
    style = json.loads(style_path.read_text(encoding="utf-8"))
    layers = style.get("layers") or []
    sources = style.get("sources") or {}
    if not isinstance(layers, list) or not isinstance(sources, dict):
        raise RuntimeError("style.json 格式不正确")

    source_features: Dict[str, List[Dict[str, Any]]] = {}
    for source_id, source in sources.items():
        if not isinstance(source, dict):
            continue
        data = source.get("data")
        if not isinstance(data, dict):
            continue
        features = data.get("features")
        if isinstance(features, list):
            source_features[source_id] = [f for f in features if isinstance(f, dict)]

    required: Dict[str, Set[str]] = {}
    for layer in layers:
        if not isinstance(layer, dict):
            continue
        if layer.get("type") != "symbol":
            continue
        source_id = layer.get("source")
        if not isinstance(source_id, str):
            continue
        features = source_features.get(source_id)
        if features is None:
            continue

        text_key = _layer_text_field_key(layer)
        if not text_key:
            continue
        fonts = _layer_text_fonts(layer)
        if not fonts:
            continue

        matched_features = _filter_features_for_layer(features, layer)
        chars: Set[int] = set()
        for f in matched_features:
            for s in _iter_feature_strings(f, text_key):
                for ch in s:
                    chars.add(ord(ch))

        if not chars:
            continue

        ranges: Set[str] = set()
        for cp in chars:
            start = (cp // 256) * 256
            end = start + 255
            ranges.add(f"{start}-{end}.pbf")
        ranges.add("0-255.pbf")

        for font_name in fonts:
            required.setdefault(font_name, set()).update(ranges)

    if not required:
        raise RuntimeError("未从 style.json 中解析出任何需要的 glyph 范围")

    return required


def _ranges_from_codepoints(codepoints: Iterable[int]) -> Set[str]:
    ranges: Set[str] = set()
    for cp in codepoints:
        if not isinstance(cp, int):
            continue
        if cp < 0 or cp > 0x10FFFF:
            continue
        start = (cp // 256) * 256
        end = start + 255
        ranges.add(f"{start}-{end}.pbf")
    ranges.add("0-255.pbf")
    return ranges


def _collect_required_ranges_from_preset(preset: str) -> Tuple[Dict[str, Set[str]], List[str]]:
    if preset != "16-langs":
        raise RuntimeError(f"未知 preset: {preset}")

    per_font_texts: Dict[str, List[str]] = {
        "Noto Sans CJK SC Regular": [
            "中文：你好，世界！【中文标点，。/？‘’“”；：～！@#¥%……&*（）《》】",
            "日本語：こんにちは世界",
            "한국어: 안녕하세요 세계",
        ],
        "Noto Naskh Arabic Regular": [
            "ئۇيغۇرچە: ياخشىمۇسىز دۇنيا",
            "العربية: مرحبا بالعالم",
        ],
        "Noto Sans Thai Regular": [
            "ภาษาไทย: สวัสดีชาวโลก",
        ],
        "Noto Sans Khmer Regular": [
            "ខ្មែរ: សួស្តី​ពិភពលោក",
        ],
        "Noto Sans Regular": [
            "English: Hello World",
            "Tiếng Việt: Xin chào thế giới",
            "Português: Olá Mundo",
            "Español: Hola Mundo",
            "Türkçe: Merhaba Dünya",
            "Română: Salut Lume",
            "Български: Здравей свят",
            "Italiano: Ciao Mondo",
            "Русский: Привет мир",
            "Bahasa Indonesia: Halo Dunia",
            "0123456789 - _ / : ; , . ! ? ( ) [ ] { } @ # ¥ % & * + =",
        ],
    }

    required: Dict[str, Set[str]] = {}
    for font_name, texts in per_font_texts.items():
        cps: Set[int] = set()
        for t in texts:
            for ch in t:
                cps.add(ord(ch))
        required[font_name] = _ranges_from_codepoints(cps)

    return required, list(per_font_texts.keys())


def _read_text_file(path: Path) -> List[str]:
    raw = path.read_text(encoding="utf-8")
    out: List[str] = []
    for line in raw.splitlines():
        s = line.strip()
        if s:
            out.append(s)
    return out


def _dir_size_bytes(p: Path) -> int:
    if not p.exists():
        return 0
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())


def _prune_glyphs(glyphs_dir: Path, required: Dict[str, Set[str]], apply: bool) -> None:
    if not glyphs_dir.exists():
        raise RuntimeError(f"glyphs-dir 不存在: {glyphs_dir}")

    total_before = _dir_size_bytes(glyphs_dir)
    deleted_files: List[Path] = []
    removed_dirs: List[Path] = []
    invalid_pbf_files: List[Path] = []

    required_fonts = set(required.keys())
    for font_name, keep_ranges in required.items():
        font_dir = glyphs_dir / font_name
        if not font_dir.exists():
            raise RuntimeError(f"缺少字体目录: {font_dir}")

        for f in font_dir.iterdir():
            if not f.is_file():
                continue
            if f.suffix != ".pbf":
                continue
            if f.name in keep_ranges:
                head = f.read_bytes()[:15]
                if head.decode("utf-8", errors="ignore") == "<!DOCTYPE html>":
                    invalid_pbf_files.append(f)
                continue
            if apply:
                f.unlink()
                deleted_files.append(f)

    if invalid_pbf_files:
        invalid_pbf_files = sorted(invalid_pbf_files)
        preview = "\n".join(str(p) for p in invalid_pbf_files[:20])
        more = ""
        if len(invalid_pbf_files) > 20:
            more = f"\n... 还有 {len(invalid_pbf_files) - 20} 个"
        raise RuntimeError(f"PBF 文件疑似为 HTML 错误页（需要先替换为真实 PBF）:\n{preview}{more}")

    if apply:
        for child in glyphs_dir.iterdir():
            if not child.is_dir():
                continue
            if child.name in required_fonts:
                continue
            shutil.rmtree(child)
            removed_dirs.append(child)

    total_after = _dir_size_bytes(glyphs_dir) if apply else total_before
    print(f"glyphs-dir: {glyphs_dir}")
    print(f"fonts: {len(required)}")
    print(f"size: {total_before} -> {total_after} bytes")
    if apply:
        print(f"deleted: {len(deleted_files)} files")
        print(f"removed_dirs: {len(removed_dirs)}")
    else:
        would_delete = 0
        for font_name, keep_ranges in required.items():
            font_dir = glyphs_dir / font_name
            for f in font_dir.iterdir():
                if f.is_file() and f.suffix == ".pbf" and f.name not in keep_ranges:
                    would_delete += 1
        would_remove_dirs = 0
        for child in glyphs_dir.iterdir():
            if child.is_dir() and child.name not in required_fonts:
                would_remove_dirs += 1
        print(f"would_delete: {would_delete} files (use --apply)")
        print(f"would_remove_dirs: {would_remove_dirs} dirs (use --apply)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font-dir", default=None)
    parser.add_argument("--out", default=".")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--bin", default=None)
    parser.add_argument("--subset-style", default=None)
    parser.add_argument("--subset-preset", default=None)
    parser.add_argument("--subset-text", action="append", default=None)
    parser.add_argument("--subset-text-file", default=None)
    parser.add_argument("--subset-font", action="append", default=None)
    parser.add_argument("--prune-glyphs-dir", default=None)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    is_prune_mode = bool(args.subset_style or args.subset_preset or args.subset_text or args.subset_text_file or args.prune_glyphs_dir)
    if is_prune_mode:
        if not args.prune_glyphs_dir:
            raise RuntimeError("裁剪模式需要提供 --prune-glyphs-dir")
        if args.subset_style and (args.subset_preset or args.subset_text or args.subset_text_file):
            raise RuntimeError("裁剪模式不可同时使用 --subset-style 与 --subset-preset/--subset-text/--subset-text-file")

        glyphs_dir = Path(args.prune_glyphs_dir).resolve()

        if args.subset_style:
            style_path = Path(args.subset_style).resolve()
            required = _collect_required_ranges_from_style(style_path)
            _prune_glyphs(glyphs_dir, required, apply=bool(args.apply))
            return 0

        preset_required: Optional[Dict[str, Set[str]]] = None
        preset_fonts: List[str] = []
        if args.subset_preset:
            preset_required, preset_fonts = _collect_required_ranges_from_preset(str(args.subset_preset))

        texts: List[str] = []
        if args.subset_text:
            texts.extend([t for t in args.subset_text if isinstance(t, str) and t.strip()])
        if args.subset_text_file:
            texts.extend(_read_text_file(Path(args.subset_text_file).resolve()))

        fonts: List[str] = []
        if args.subset_font:
            fonts = [f for f in args.subset_font if isinstance(f, str) and f.strip()]
        elif preset_fonts:
            fonts = preset_fonts

        if preset_required is not None and not texts and not args.subset_font:
            _prune_glyphs(glyphs_dir, preset_required, apply=bool(args.apply))
            return 0

        if not fonts:
            raise RuntimeError("裁剪模式需要提供 --subset-font，或使用 --subset-preset 来指定默认字体集合")
        if not texts and preset_required is None:
            raise RuntimeError("裁剪模式需要提供 --subset-style 或 --subset-preset 或 --subset-text/--subset-text-file")

        cps: Set[int] = set()
        for t in texts:
            for ch in t:
                cps.add(ord(ch))
        keep_ranges = _ranges_from_codepoints(cps)

        required: Dict[str, Set[str]] = {}
        for font_name in fonts:
            required[font_name] = set(keep_ranges)
            if preset_required and font_name in preset_required:
                required[font_name].update(preset_required[font_name])

        _prune_glyphs(glyphs_dir, required, apply=bool(args.apply))
        return 0

    font_dir = Path(args.font_dir).resolve() if args.font_dir else script_dir
    out_dir = Path(args.out).resolve()

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
