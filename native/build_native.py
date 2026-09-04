from __future__ import annotations

import argparse
import platform
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def build(*, quiet: bool = False) -> Path | None:
    system = platform.system().lower()
    if system not in {"linux", "darwin"}:
        if not quiet:
            print(f"Native acceleration skipped on {system}; Python fallback remains active.")
        return None
    cc = shutil.which("gcc")
    cxx = shutil.which("g++") or shutil.which("clang++")
    if not cc or not cxx:
        if not quiet:
            print("Native compiler unavailable; Python fallback remains active.")
        return None

    c_object = ROOT / "rolling_stats.o"
    cpp_object = ROOT / "supertrend.o"
    library = ROOT / ("libshare_scan_native.dylib" if system == "darwin" else "libshare_scan_native.so")
    commands = [
        [cc, "-O3", "-fPIC", "-c", str(ROOT / "rolling_stats.c"), "-o", str(c_object)],
        [cxx, "-O3", "-std=c++17", "-fPIC", "-c", str(ROOT / "supertrend.cpp"), "-o", str(cpp_object)],
        [cxx, "-shared", str(c_object), str(cpp_object), "-o", str(library)],
    ]
    for command in commands:
        subprocess.run(command, check=True, capture_output=quiet, text=True)
    c_object.unlink(missing_ok=True)
    cpp_object.unlink(missing_ok=True)
    if not quiet:
        print(f"Native acceleration built: {library}")
    return library


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    build(quiet=args.quiet)


if __name__ == "__main__":
    main()
