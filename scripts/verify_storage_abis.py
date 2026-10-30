#!/usr/bin/env python3
"""Regression-check both PV7 native ABIs and the shared storage DEX.

Use after running embed_storage_native.py on ARM64 or x86_64 builds.
This checks packaged artifacts without requiring a Gradle/Maven connection.
"""
import hashlib
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
APK = ROOT / "app/src/main/assets/pv7/templates/pv7-host-template.apk"
ABIS = {"arm64-v8a": 0xB7, "x86_64": 0x3E}


def validate_elf(raw: bytes, abi: str) -> None:
    assert len(raw) > 64 and raw[:5] == b"\x7fELF\x02", f"{abi}: not a 64-bit ELF"
    assert raw[5] == 1, f"{abi}: not little-endian"
    assert int.from_bytes(raw[18:20], "little") == ABIS[abi], f"{abi}: wrong machine"
    for token in (b"GuestSharedStorage", b"getObbDir", b"pv7.guestVersionCode"):
        assert token in raw, f"{abi}: generic storage JNI marker {token!r} missing"


def main() -> None:
    with zipfile.ZipFile(APK) as archive:
        assert archive.testzip() is None, "corrupted host template"
        names = archive.namelist()
        assert len(names) == len(set(names)), "duplicate ZIP members"
        dex = archive.read("classes2.dex")
        for classname in (b"Pv7GuestStoragePaths", b"Pv7GuestStorageBridge",
                          b"Pv7GameStorageDocumentsProvider"):
            assert classname in dex, f"{classname!r} missing from DEX"
        for abi in ABIS:
            entry = f"lib/{abi}/libpv7host.so"
            raw = archive.read(entry)
            validate_elf(raw, abi)
            print("PV7_ABI_STORAGE_VALID", abi, len(raw),
                  hashlib.sha256(raw).hexdigest()[:20])
    print("PV7_ABI_TEMPLATE_PASS")


if __name__ == "__main__":
    main()