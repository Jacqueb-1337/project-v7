#!/usr/bin/env python3
"""Safely package a PV7 native runtime for one Android ABI.

Verifies ELF architecture, preserves ZIP metadata and every other entry,
checks the storage DEX, and keeps a backup on a staging drive.
"""
import argparse
import hashlib
import pathlib
import shutil
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "app/src/main/assets/pv7/templates/pv7-host-template.apk"
MACHINES = {"arm64-v8a": 0xB7, "x86_64": 0x3E}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--native-so", type=pathlib.Path, required=True)
    parser.add_argument("--abi", choices=sorted(MACHINES), default="arm64-v8a")
    parser.add_argument("--staging-dir", type=pathlib.Path)
    args = parser.parse_args()
    entry = f"lib/{args.abi}/libpv7host.so"
    native = args.native_so.read_bytes()
    if (
        len(native) < 64
        or native[:5] != b"\x7fELF\x02"
        or native[5] != 1
        or int.from_bytes(native[18:20], "little") != MACHINES[args.abi]
    ):
        raise SystemExit(f"Refusing incorrect ELF architecture for {args.abi}")

    staging = args.staging_dir or pathlib.Path(tempfile.gettempdir()) / "pv7-native-stage"
    staging.mkdir(parents=True, exist_ok=True)
    backup = staging / f"pv7-template-before-{args.abi}.apk"
    candidate = staging / f"pv7-template-new-{args.abi}.apk"
    if not TEMPLATE.is_file():
        raise SystemExit("PV7 template is missing")
    shutil.copy2(TEMPLATE, backup)
    with zipfile.ZipFile(backup, "r") as old, zipfile.ZipFile(candidate, "w") as new:
        infos = old.infolist()
        if not any(info.filename == entry for info in infos):
            raise SystemExit(f"Template missing {entry}")
        if b"Pv7GuestStoragePaths" not in old.read("classes2.dex"):
            raise SystemExit("Generic Java storage resolver missing")
        for info in infos:
            new.writestr(info, native if info.filename == entry else old.read(info.filename))

    with zipfile.ZipFile(backup) as old, zipfile.ZipFile(candidate) as replacement:
        if old.namelist() != replacement.namelist():
            raise SystemExit("ZIP file entries changed")
        for name in old.namelist():
            old_data = old.read(name)
            new_data = replacement.read(name)
            if name == entry:
                if new_data != native:
                    raise SystemExit("Native library data mismatch")
            elif sha256(old_data) != sha256(new_data):
                raise SystemExit(f"Unrelated ZIP entry changed: {name}")
        if replacement.testzip() is not None:
            raise SystemExit("ZIP integrity verification failed")

    shutil.copyfile(candidate, TEMPLATE)
    with zipfile.ZipFile(TEMPLATE) as final:
        if final.read(entry) != native:
            # Restore from the preserved on-C: backup if write verification fails.
            shutil.copyfile(backup, TEMPLATE)
            raise SystemExit("Final verification failed and template restored")
    print("PV7_NATIVE_TEMPLATE_REPLACED", args.abi, len(native), sha256(native))
    print("PV7_TEMPLATE_BACKUP", backup)
    print("PV7_TEMPLATE_INTEGRITY_OK", TEMPLATE.stat().st_size)


if __name__ == "__main__":
    main()