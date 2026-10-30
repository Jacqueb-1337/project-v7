#!/usr/bin/env python3
"""Package a verified native PV7 runtime in the generic host template.

Only lib/arm64-v8a/libpv7host.so is replaced. Preserve every other ZIP entry,
including the x86_64 runtime and the secondary Java storage provider DEX.
Stages safely outside the (possibly almost full) source drive.
"""
import argparse
import pathlib
import shutil
import tempfile
import zipfile
import hashlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "app/src/main/assets/pv7/templates/pv7-host-template.apk"
ENTRY = "lib/arm64-v8a/libpv7host.so"

def digest(data):
    return hashlib.sha256(data).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--native-so",type=pathlib.Path,required=True)
    parser.add_argument("--staging-dir",type=pathlib.Path,default=None)
    args = parser.parse_args()
    if not args.native_so.is_file():
        raise SystemExit("Missing native runtime: "+str(args.native_so))
    new = args.native_so.read_bytes()
    if new[:5] != b"\x7fELF\x02" or new[18:20] != b"\xb7\x00":
        raise SystemExit("Refusing a non-ARM64 ELF host binary")
    staging = args.staging_dir or pathlib.Path(tempfile.gettempdir()) / "pv7-native-stage"
    staging.mkdir(parents=True,exist_ok=True)
    backup = staging / "pv7-template-before-native.apk"
    candidate = staging / "pv7-template-new-native.apk"
    if not TEMPLATE.is_file():
        raise SystemExit("PV7 host template missing")
    shutil.copyfile(TEMPLATE,backup)
    with zipfile.ZipFile(TEMPLATE,"r") as src,zipfile.ZipFile(candidate,"w") as dst:
        infos = src.infolist()
        if not any(info.filename == ENTRY for info in infos):
            raise SystemExit("Template has no ARM64 host library")
        for info in infos:
            payload = new if info.filename==ENTRY else src.read(info.filename)
            dst.writestr(info,payload)
    with zipfile.ZipFile(backup) as old,zipfile.ZipFile(candidate) as ready:
        oldnames = set(old.namelist())
        if oldnames!=set(ready.namelist()):
            raise SystemExit("Template entries changed unexpectedly")
        for name in oldnames:
            if name==ENTRY:
                if ready.read(name)!=new: raise SystemExit("Incorrect ARM64 library")
            elif digest(old.read(name))!=digest(ready.read(name)):
                raise SystemExit("Unrelated template entry changed: "+name)
        if not ready.testzip() is None:
            raise SystemExit("Template ZIP integrity check failed")
        if b"Pv7GuestStoragePaths" not in ready.read("classes2.dex"):
            raise SystemExit("Shared Java storage bridge not embedded")
    # Overwrite rather than create a second complete copy on the source drive.
    # The backup remains on the staging drive until verified after copying.
    shutil.copyfile(candidate,TEMPLATE)
    with zipfile.ZipFile(TEMPLATE) as check:
        if check.read(ENTRY)!=new: raise SystemExit("Template native copy verification failed")
    print("PV7_NATIVE_TEMPLATE_REPLACED",len(new),"sha256",digest(new))
    print("PV7_TEMPLATE_BACKUP",backup)
    print("PV7_TEMPLATE_INTEGRITY_OK",TEMPLATE.stat().st_size)

if __name__=="__main__":
    main()