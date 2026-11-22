#!/usr/bin/env python3
"""Verify every native library packaged in a QA APK is Android 16 KiB safe."""
from __future__ import annotations
import argparse
import struct
import sys
import zipfile

PAGE = 0x4000

def check_elf(data: bytes, name: str) -> list[str]:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        return [f"{name}: not little-endian ELF64"]
    phoff = struct.unpack_from("<Q", data, 32)[0]
    entsize, count = struct.unpack_from("<HH", data, 54)
    if entsize < 56 or not count or phoff + entsize * count > len(data):
        return [f"{name}: invalid program header table"]
    errors, loads = [], 0
    for i in range(count):
        ptype, flags, offset, vaddr, paddr, fsize, msize, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + i * entsize)
        if ptype != 1:
            continue
        loads += 1
        if alignment < PAGE or alignment & (alignment - 1) or (vaddr - offset) % PAGE:
            errors.append(f"{name}: LOAD {i} not 16KiB aligned (p_align={alignment:#x})")
    if loads == 0:
        errors.append(f"{name}: no LOAD segments")
    return errors

def check_apk(path: str) -> tuple[int, list[str]]:
    errors, count = [], 0
    with zipfile.ZipFile(path) as z:
        entries = [i for i in z.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
        if not entries:
            return 0, ["APK has no native libraries"]
        for info in entries:
            count += 1
            if info.compress_type == zipfile.ZIP_STORED:
                assert z.fp is not None
                z.fp.seek(info.header_offset)
                header = z.fp.read(30)
                if len(header) != 30 or header[:4] != b"PK\x03\x04":
                    errors.append(f"{info.filename}: invalid ZIP local header")
                else:
                    namelen, extralen = struct.unpack_from("<HH", header, 26)
                    offset = info.header_offset + 30 + namelen + extralen
                    if offset % PAGE:
                        errors.append(f"{info.filename}: stored ZIP entry not 16KiB aligned")
            elif info.compress_type != zipfile.ZIP_DEFLATED:
                errors.append(f"{info.filename}: unsupported native compression")
            errors.extend(check_elf(z.read(info), info.filename))
    return count, errors

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    args = parser.parse_args()
    try:
        count, problems = check_apk(args.apk)
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        print(f"16KiB native inspection failed: {exc}", file=sys.stderr)
        return 1
    print(f"16KiB APK native check: {count} libraries, {len(problems)} violations")
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0

if __name__ == "__main__":
    raise SystemExit(main())
