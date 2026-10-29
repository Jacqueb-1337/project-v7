#!/usr/bin/env python3
"""Rebuild the generic game-files SAF provider into PV7's host template.

Requires a local JDK and Android SDK build-tools 36.1.0; no third-party Python
packages. Does NOT rebuild the native runtime, alter game APKs, or sign releases.
The input template is supplied by the PV7 wrapper project. This step adds a
distinct classes2.dex to make the provider available to every generated wrapper.

python scripts/embed_storage_provider.py
"""
import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "host/src/app/projectv7/wrapper/Pv7GameStorageDocumentsProvider.java"
TEMPLATE = ROOT / "app/src/main/assets/pv7/templates/pv7-host-template.apk"


def binary(name: str):
    path = shutil.which(name)
    if not path:
        raise RuntimeError(f"Missing {name}; install a JDK and Android SDK")
    return path


def sdk_root() -> pathlib.Path:
    user = os.environ.get("LOCALAPPDATA")
    if not user:
        raise RuntimeError("LOCALAPPDATA not set; this script currently targets Windows")
    return pathlib.Path(user) / "Android/Sdk"


def run(*command: str):
    subprocess.run([str(x) for x in command], check=True)


def main():
    if not SOURCE.is_file() or not TEMPLATE.is_file():
        raise RuntimeError("Missing provider source or PV7 host template")
    sdk = sdk_root()
    jar = sdk / "platforms/android-36/android.jar"
    d8 = sdk / "build-tools/36.1.0/d8.bat"
    if not jar.is_file() or not d8.is_file():
        raise RuntimeError("Android SDK platform 36 and build-tools 36.1.0 are required")

    with tempfile.TemporaryDirectory(prefix="pv7-saf-") as temp:
        base = pathlib.Path(temp)
        classes = base / "classes"
        classes.mkdir()
        run(binary("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-classpath", jar, "-d", classes, SOURCE)
        archive = base / "provider.jar"
        run(binary("jar"), "--create", "--file", archive, "-C", classes, ".")
        dex_dir = base / "dex"
        dex_dir.mkdir()
        run(d8, "--min-api", "24", "--lib", jar, "--output", dex_dir, archive)
        dex = dex_dir / "classes.dex"
        if not dex.is_file():
            raise RuntimeError("D8 did not produce classes.dex")

        temp_apk = base / "pv7-host-template.apk"
        with zipfile.ZipFile(TEMPLATE) as original, zipfile.ZipFile(temp_apk, "w") as out:
            names = set(original.namelist())
            if "classes.dex" not in names:
                raise RuntimeError("The host template must include classes.dex")
            if "classes3.dex" in names:
                raise RuntimeError("Unexpected multidex template; check DEX order")
            for info in original.infolist():
                if info.filename == "classes2.dex":
                    continue  # Deterministically replace the generated provider DEX.
                out.writestr(info, original.read(info.filename))
            out.write(dex, "classes2.dex", compress_type=zipfile.ZIP_DEFLATED)

        with zipfile.ZipFile(temp_apk) as ready:
            assert "classes.dex" in ready.namelist()
            assert len(ready.read("classes2.dex")) > 1024
        shutil.copyfile(temp_apk, TEMPLATE)
        print(f"Verified provider DEX in {TEMPLATE} ({TEMPLATE.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
