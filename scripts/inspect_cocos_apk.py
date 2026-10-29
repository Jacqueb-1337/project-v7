#!/usr/bin/env python3
"""Static JNI/ELF inventory for legacy ARM32 Cocos2d-x Android APKs.

Reads APK and ELF bytes, never runs the game. Used to scope new engine backend
implementations without checking copyrighted binaries into the repository.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import struct
import zipfile

MAX_ELF = 256 * 1024 * 1024


def unpack(fmt: str, data: bytes, offset: int):
    if offset < 0 or offset + struct.calcsize(fmt) > len(data):
        raise ValueError("Truncated or malformed ELF section")
    return struct.unpack_from(fmt, data, offset)


def section_bytes(data: bytes, section: tuple[int, ...]) -> bytes:
    offset, size = section[4], section[5]
    if size > len(data) or offset > len(data) - size:
        raise ValueError("ELF section is out of bounds")
    return data[offset: offset + size]


def elf32_inventory(data: bytes) -> dict:
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise ValueError("Not an ELF binary")
    if data[4] != 1 or data[5] != 1:
        raise ValueError("Only ELF32 little-endian is supported")
    machine, = unpack("<H", data, 18)
    if machine != 40:
        raise ValueError("Only ARM32 (EM_ARM) is supported")
    shoff, = unpack("<I", data, 32)
    shentsize, shnum = unpack("<HH", data, 46)
    if shnum == 0:
        return {"architecture": "ELF32 ARM little-endian", "needed": [],
                "cocos_jni_exports": [], "total_dynamic_exports": 0}
    if shentsize < 40 or shnum > 8192:
        raise ValueError("Invalid ELF section table")
    if shoff + shentsize * shnum > len(data):
        raise ValueError("ELF section table out of bounds")
    sections = [
        unpack("<IIIIIIIIII", data, shoff + i * shentsize)
        for i in range(shnum)
    ]

    def name(table: bytes, offset: int) -> str:
        if offset >= len(table):
            return ""
        end = table.find(b"\0", offset)
        if end < 0:
            return ""
        return table[offset:end].decode("utf-8", "replace")

    needed, exported_jni = set(), set()
    total_exports = 0
    for section in sections:
        kind, entsize, linked = section[1], section[9], section[6]
        if kind not in (6, 11):  # SHT_DYNAMIC, SHT_DYNSYM
            continue
        if linked >= len(sections):
            raise ValueError("Invalid ELF string-table index")
        strings = section_bytes(data, sections[linked])
        contents = section_bytes(data, section)
        if kind == 6:
            if entsize and entsize < 8:
                raise ValueError("Invalid dynamic section entry")
            for pos in range(0, len(contents) - 7, entsize or 8):
                tag, value = unpack("<iI", contents, pos)
                if tag == 1:
                    needed.add(name(strings, value))
        if kind == 11:
            if entsize < 16:
                raise ValueError("Invalid dynamic symbol entry")
            for pos in range(0, len(contents) - 15, entsize):
                str_offset, _, _, _, _, section_index = unpack(
                    "<IIIBBH", contents, pos
                )
                if section_index == 0:
                    continue
                symbol = name(strings, str_offset)
                if symbol:
                    total_exports += 1
                if symbol == "JNI_OnLoad" or symbol.startswith("Java_org_cocos2dx_"):
                    exported_jni.add(symbol)
    return {
        "architecture": "ELF32 ARM little-endian",
        "needed": sorted(needed),
        "cocos_jni_exports": sorted(exported_jni),
        "total_dynamic_exports": total_exports,
    }


def inspect_apk(path: pathlib.Path) -> dict:
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        candidates = [
            f"lib/{abi}/{filename}"
            for abi in ("armeabi-v7a", "armeabi")
            for filename in ("libgame.so", "libcocos2dcpp.so")
        ]
        selected = next((item for item in candidates if item in names), None)
        if not selected:
            raise ValueError("No ARM32 Cocos2d-x native library in the APK")
        entry = apk.getinfo(selected)
        if entry.file_size > MAX_ELF:
            raise ValueError("Native ELF exceeds size limit")
        image = apk.read(selected)
        return {
            "apk": path.name,
            "native_library": selected,
            "cocos_class_tree": any(n.startswith("org/cocos2dx/lib/") for n in names),
            **elf32_inventory(image),
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path, help="Local legacy Android APK")
    args = parser.parse_args()
    print(json.dumps(inspect_apk(args.apk), indent=2))


if __name__ == "__main__":
    main()
