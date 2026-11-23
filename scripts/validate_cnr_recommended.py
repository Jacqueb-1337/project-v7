#!/usr/bin/env python3
"""Offline consistency gate for the CNR recommended PV7 module setup."""
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "catalog"
CNR_DIR = CATALOG / "modules/games/com.joydo.minestrikenew/cnr-compat"
MANIFEST = CNR_DIR / "module.json"
PROFILE = CATALOG / "profiles/com.joydo.minestrikenew/3.0.2.json"
PAYLOAD = CNR_DIR / "payload"
EXPECTED_HOST = "73e227106acf659709f92b4b2d75dc008b0b43e3a624f08e89f1f1e82ca5dad6"
EXPECTED_MODS = ("CNRModManager", "CNRMod", "CNRSettingsMod", "GraphicsSettings")

def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def main() -> None:
    index = json.loads((CATALOG / "index.json").read_text(encoding="utf-8"))
    modules = {
        x["id"]: x for name in index["modules"]
        for x in [json.loads((CATALOG / name).read_text(encoding="utf-8"))]
    }
    profiles = [
        json.loads((CATALOG / name).read_text(encoding="utf-8"))
        for name in index["profiles"]
    ]
    cnr = json.loads(MANIFEST.read_text(encoding="utf-8"))
    profile = json.loads(PROFILE.read_text(encoding="utf-8"))
    assert profile in profiles
    assert profile["tested"]
    assert profile["recommendedModules"] == [
        "core-compat", "unity-4.6-compat", "cnr-compat"]
    assert all(x in modules for x in profile["recommendedModules"])
    assert all(x in modules for x in cnr["dependencies"])
    assert cnr["version"] == "1.0.10"
    assert "lib/arm64-v8a/libpv7host.so.pv7z" in cnr["payloadFiles"]
    for f in cnr["payloadFiles"]:
        assert (PAYLOAD / f).is_file(), f"Missing payload: {f}"

    host = (PAYLOAD / "lib/arm64-v8a/libpv7host.so.pv7z").read_bytes()
    native = gzip.decompress(host)
    assert digest(native) == EXPECTED_HOST, "Verified native host hash drift"
    assert b"PV7GAME render-worker-wait" in native
    assert b"PV7GAME render-worker-timeout" in native
    bootstrap_props = (
        PAYLOAD / "assets/pv7/host.properties").read_text(encoding="utf-8")
    for mod in EXPECTED_MODS:
        path = PAYLOAD / ("assets/bootstrap/" + mod + ".dll")
        sha = PAYLOAD / ("assets/bootstrap/" + mod + ".dll.sha256")
        assert digest(path.read_bytes()) == sha.read_text(encoding="ascii").strip()
        assert "toFilesRelative=CNRMods/" + mod + ".dll" in bootstrap_props
    for idx in (0, 2, 3, 4):
        assert f"bootstrap.{idx}.preserveCustom=true" in bootstrap_props

    template = ROOT / "app/src/main/assets/pv7/templates/pv7-host-template.apk"
    with zipfile.ZipFile(template) as apk:
        primary = apk.read("classes.dex")
        for marker in (b"seedBootstrapFiles", b"Seeded bootstrap", b"Bootstrap hash mismatch"):
            assert marker in primary, f"Host template missing bootstrap code: {marker!r}"
        generic = apk.read("lib/arm64-v8a/libpv7host.so")
        assert digest(generic) != EXPECTED_HOST
        assert any(
            b"CNRModsDocumentsProvider" in apk.read(name)
            for name in apk.namelist() if name.endswith(".dex")
        ), "Host template missing the DocumentsProvider class"
    print("VALID: complete recommended profile, dependencies and payloads")
    print("VALID: managed DLL hashes and preserve-custom bootstrap rules")
    print("VALID: exact known-good CNR ARM64 native library:", EXPECTED_HOST)
    print("Native compression:", len(native), "->", len(host), "bytes")
    print("VALID: generic template remains separate and includes CNR provider class")

if __name__ == "__main__":
    main()
