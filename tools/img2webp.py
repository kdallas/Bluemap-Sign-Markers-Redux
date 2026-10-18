#!/usr/bin/env python3
"""Convert marker icon images to lossless WebP.

    python3 tools/img2webp.py                       # in place, under src/main/resources/markers
    python3 tools/img2webp.py --out /tmp/webp       # mirror the tree into /tmp/webp instead
    python3 tools/img2webp.py path/to/set2 --dry-run
    python3 tools/img2webp.py --remove-source       # delete each PNG after its WebP verifies

By default every raster image under the marker directories is converted alongside its
source (portal.png -> portal.webp); the sources are left alone. With --out the directory
structure is mirrored below the output directory, so set1/portal.png and set2/portal.png
stay in separate subdirectories rather than colliding on one name.

"Lossless" here is verified, not assumed: each WebP is decoded again and compared with the
source pixel for pixel (as RGBA), and only a file that matches is kept. The encoder runs
with exact=True, so colour values hidden under fully transparent pixels survive too - the
usual reason a "lossless" WebP round-trip still differs from its PNG.

SVG files are deliberately not converted. An SVG is vector data; turning it into pixels is
a lossy decision about resolution, not a re-encoding, so those files are left untouched.

Requires Pillow with WebP support:
    Debian/Ubuntu: sudo apt install python3-pil
    elsewhere:     python3 -m pip install Pillow
"""

import argparse
import os
import sys
from pathlib import Path

try:
    from PIL import Image, features
except ImportError:
    sys.exit(
        "error: Pillow is required to encode WebP.\n"
        "  Debian/Ubuntu: sudo apt install python3-pil\n"
        "  elsewhere:     python3 -m pip install Pillow"
    )

if not features.check("webp"):
    sys.exit(
        "error: this Pillow build has no WebP support.\n"
        "  Debian/Ubuntu: sudo apt install python3-pil\n"
        "  elsewhere:     python3 -m pip install --upgrade Pillow"
    )

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INPUT = REPO_ROOT / "src" / "main" / "resources" / "markers"

# Raster formats Pillow can read. .webp is not here: converting a WebP to WebP is pointless
# and would let a previous run's output be picked up as a source.
SOURCE_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".gif", ".tif", ".tiff"}


class VerificationError(Exception):
    pass


def display(path):
    """Repo-relative where possible, so output is short and clickable."""
    try:
        return path.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return path


def collect(inputs):
    """Expand the CLI arguments into an ordered, de-duplicated list of (source, base).

    `base` is the directory the source was found under; it is what --out mirrors. For a
    file passed directly, base is None and the file lands at the top of the output dir.
    """
    found = []
    for item in inputs:
        if item.is_dir():
            for path in sorted(item.rglob("*")):
                if path.is_file() and path.suffix.lower() in SOURCE_EXTS:
                    found.append((path, item))
        elif item.is_file():
            found.append((item, None))
        else:
            raise SystemExit(f"error: no such file or directory: {item}")

    seen = set()
    unique = []
    for src, base in found:
        key = src.resolve()
        if key in seen:
            continue
        seen.add(key)
        unique.append((src, base))
    return unique


def output_path(src, base, out_dir):
    if out_dir is None:
        return src.with_suffix(".webp")
    if base is None:
        rel = Path(src.name)
    else:
        # Includes the input directory's own name: markers/set1/x.png -> set1/x.webp,
        # which keeps same-named icons from different sets apart.
        rel = src.relative_to(base.parent)
    return out_dir / rel.with_suffix(".webp")


def verify(source_rgba, path):
    """Decode `path` and require it to match the source exactly, pixel for pixel."""
    with Image.open(path) as im:
        im.load()
        if im.format != "WEBP":
            raise VerificationError(f"decoded as {im.format}, not WEBP")
        if im.size != source_rgba.size:
            raise VerificationError(f"size {im.size[0]}x{im.size[1]} != {source_rgba.size[0]}x{source_rgba.size[1]}")
        got = im.convert("RGBA")

    want = source_rgba.tobytes()
    have = got.tobytes()
    if want != have:
        width = source_rgba.size[0]
        for i in range(0, len(want), 4):
            if want[i:i + 4] != have[i:i + 4]:
                px, py = (i // 4) % width, (i // 4) // width
                raise VerificationError(
                    f"pixel difference at ({px}, {py}): "
                    f"source RGBA {tuple(want[i:i + 4])}, decoded {tuple(have[i:i + 4])}"
                )
        raise VerificationError("pixel data differs")


def convert(src, dst, args):
    """Return (status, detail). status is 'ok', 'skip' or 'failed'."""
    if dst.exists() and not args.overwrite:
        return "skip", "output exists (use --overwrite)"

    with Image.open(src) as im:
        frames = getattr(im, "n_frames", 1)
        if frames > 1:
            return "skip", f"animated ({frames} frames)"
        im.load()
        rgba = im.convert("RGBA")
        icc_profile = im.info.get("icc_profile")

    # An icon with no transparency is stored as RGB: an all-255 alpha channel costs bytes
    # and the comparison below still passes, because a decoded RGB image converts back to
    # RGBA with alpha 255.
    opaque = rgba.getchannel("A").getextrema() == (255, 255)
    out = rgba.convert("RGB") if opaque else rgba

    save_args = dict(
        format="WEBP",
        lossless=True,
        quality=args.quality,   # lossless: compression effort, 100 = slowest/smallest
        method=args.method,     # 0-6, 6 = slowest/smallest
    )
    if not opaque:
        save_args["exact"] = True
    if icc_profile:
        save_args["icc_profile"] = icc_profile

    # Write beside the target and rename only after verification, so an interrupted run or
    # a bad encoding can never leave a half-written .webp under the final name.
    tmp = dst.with_name(dst.name + ".part")
    try:
        out.save(tmp, **save_args)
        verify(rgba, tmp)
        os.replace(tmp, dst)
    except VerificationError as exc:
        tmp.unlink(missing_ok=True)
        return "failed", str(exc)
    except OSError as exc:
        tmp.unlink(missing_ok=True)
        return "failed", str(exc)

    if args.remove_source:
        src.unlink()
    return "ok", ""


def human_size(n):
    if n < 1024:
        return f"{n} B"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f} KiB"
    return f"{n / (1024 * 1024):.1f} MiB"


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Convert marker icons to lossless WebP, verifying each output pixel for pixel.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "examples:\n"
            "  python3 tools/img2webp.py\n"
            "  python3 tools/img2webp.py --out /tmp/webp\n"
            "  python3 tools/img2webp.py src/main/resources/markers/set2 --dry-run\n"
        ),
    )
    parser.add_argument("inputs", nargs="*", type=Path, metavar="DIR",
                        help=f"directories (or files) to scan; default: {display(DEFAULT_INPUT)}")
    parser.add_argument("--out", type=Path, metavar="DIR",
                        help="write .webp files below DIR, mirroring each input directory's name, "
                             "instead of alongside the sources")
    parser.add_argument("--overwrite", action="store_true",
                        help="replace existing .webp files (default: skip them)")
    parser.add_argument("--remove-source", action="store_true",
                        help="delete each source file after its WebP has been verified; there is no undo")
    parser.add_argument("--quality", type=int, default=100, metavar="0-100",
                        help="lossless compression effort, 0 (fastest) to 100 (smallest); default 100")
    parser.add_argument("--method", type=int, default=6, metavar="0-6",
                        help="encoder method, 0 (fastest) to 6 (slowest, smallest); default 6")
    parser.add_argument("--dry-run", action="store_true",
                        help="print what would be converted without writing anything")
    args = parser.parse_args(argv)

    if not 0 <= args.quality <= 100:
        parser.error("--quality must be between 0 and 100")
    if not 0 <= args.method <= 6:
        parser.error("--method must be between 0 and 6")

    inputs = args.inputs or [DEFAULT_INPUT]
    jobs = [(src, output_path(src, base, args.out)) for src, base in collect(inputs)]
    if not jobs:
        print("No raster images found.")
        return 0

    ok = skipped = failed = 0
    total_src = total_dst = 0

    for src, dst in jobs:
        if args.dry_run:
            exists = dst.exists() and not args.overwrite
            print(f"would convert {display(src)} -> {display(dst)}" + ("  [exists, would skip]" if exists else ""))
            if exists:
                skipped += 1
            else:
                ok += 1
            continue

        dst.parent.mkdir(parents=True, exist_ok=True)
        # Measured before conversion: --remove-source may delete the source below.
        src_size = src.stat().st_size
        status, detail = convert(src, dst, args)

        if status == "ok":
            dst_size = dst.stat().st_size
            total_src += src_size
            total_dst += dst_size
            change = (dst_size - src_size) / src_size * 100 if src_size else 0.0
            print(f"{display(src)} -> {display(dst)}  "
                  f"{human_size(src_size)} -> {human_size(dst_size)} ({change:+.0f}%)  verified")
            ok += 1
        elif status == "skip":
            print(f"skip   {display(src)}: {detail}")
            skipped += 1
        else:
            print(f"FAILED {display(src)}: {detail}")
            failed += 1

    print()
    if args.dry_run:
        print(f"Dry run: {ok} image(s) would be converted, {skipped} skipped.")
        return 0

    saved = total_src - total_dst
    print(f"Converted {ok}, skipped {skipped}, failed {failed}.")
    if ok:
        direction = "smaller" if saved >= 0 else "larger"
        print(f"Total: {human_size(total_src)} -> {human_size(total_dst)} "
              f"({human_size(abs(saved))} {direction}).")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
