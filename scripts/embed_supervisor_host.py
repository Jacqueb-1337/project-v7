#!/usr/bin/env python3
"""Compile the shared PV7 supervisor into the default wrapper template.
Pass clean generic native builds for both supported host ABIs. Game-specific
native overrides are separate catalog payloads and never used as template input.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
import hashlib

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "app/src/main/assets/pv7/templates/pv7-host-template.apk"
HOST = ROOT / "host/src/app/projectv7/wrapper"
LOGO = ROOT / "host/assets/pv7-splash-logo.png"
MARKERS = (b"nativeGuestFrameCount", b"nativeGuestPresentCount",
           b"nativeGuestStartupStage", b"nativeSetGuestBackgrounded",
           b"nativeSetGuestHostSurface", b"RuntimeCodeCacheMiB")

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arm64", type=Path, required=True)
    parser.add_argument("--x86-64", type=Path, required=True)
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    android = sdk / "platforms/android-36/android.jar"
    d8 = sdk / "build-tools/36.1.0" / ("d8.bat" if os.name == "nt" else "d8")
    replacements = {}
    for abi, path, machine in (("arm64-v8a", args.arm64, 183),
                               ("x86_64", args.x86_64, 62)):
        data = path.read_bytes()
        assert data[:6] == b"\x7fELF\x02\x01"
        assert int.from_bytes(data[18:20], "little") == machine, abi
        for marker in MARKERS:
            assert marker in data, (abi, marker)
        replacements[f"lib/{abi}/libpv7host.so"] = data
        print(abi, hashlib.sha256(data).hexdigest())
    # Preserve secondary dex (generic SAF provider) without duplicate classes.
    sources = sorted(p for p in HOST.glob("*.java") if not p.name.startswith("Pv7"))
    with tempfile.TemporaryDirectory(prefix="pv7-host-") as temp:
        base = Path(temp)
        classes = base / "classes"; classes.mkdir()
        dex = base / "dex"; dex.mkdir()
        archive = base / "host.jar"
        run("javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-classpath", android, "-d", classes, *sources)
        run("jar", "--create", "--file", archive, "-C", classes, ".")
        run(d8, "--min-api", "24", "--lib", android, "--output", dex, archive)
        replacements["classes.dex"] = (dex / "classes.dex").read_bytes()
        replacements["assets/pv7/splash-logo.png"] = LOGO.read_bytes()
        primary = replacements["classes.dex"]
        for marker in (b"PerformanceOverlay", b"GuestRestartActivity", b"Show text background",
                       b"PV7 supervisor", b"guest-launch-cache.", b"seedBootstrapFiles"):
            assert marker in primary, marker
        output = base / "template.apk"
        with zipfile.ZipFile(TEMPLATE) as src, zipfile.ZipFile(output, "w") as dst:
            for i in src.infolist():
                if i.filename.upper().startswith("META-INF/") and i.filename.upper().endswith(
                        (".RSA", ".DSA", ".EC", ".SF", "MANIFEST.MF")):
                    continue
                dst.writestr(i, replacements.pop(i.filename, None) or src.read(i.filename))
            for name, data in replacements.items():
                dst.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(output) as new, zipfile.ZipFile(TEMPLATE) as old:
            assert new.testzip() is None
            assert new.read("classes2.dex") == old.read("classes2.dex")
            assert new.read("AndroidManifest.xml") == old.read("AndroidManifest.xml")
            assert new.read("classes.dex") == primary
        staged = TEMPLATE.with_suffix(".apk.next")
        shutil.copyfile(output, staged)
        os.replace(staged, TEMPLATE)
    print("Shared supervisor host template built; both native ABIs and SAF DEX verified.")

if __name__ == "__main__":
    main()
