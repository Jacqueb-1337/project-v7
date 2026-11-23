#!/usr/bin/env python3
"""Pin the verified CNR managed mod stack in the PV7 CNR compatibility payload.

The CNR mod repository remains the authority for version numbers and DLL bytes.
Only the CNR game's catalog module is modified; the generic Unity module stays
reusable by other games. Existing user data/installed CNRMods files are never
replaced by this script.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CNR_MODULE = ROOT / "catalog/modules/games/com.joydo.minestrikenew/cnr-compat"
PROFILE = ROOT / "catalog/profiles/com.joydo.minestrikenew/3.0.2.json"
PINNED_MODS = (
    "CNRModManager",
    "CNRMod",
    "CNRSettingsMod",
    "GraphicsSettings",
)
BOOTSTRAP_INDEX = {
    "CNRModManager": 0,
    "CNRMod": 2,
    "CNRSettingsMod": 3,
    "GraphicsSettings": 4,
}

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-mods", type=Path, required=True)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    source = args.source_mods
    catalog = json.loads((source / "repo.json").read_text(encoding="utf-8"))
    versioned = {mod.get("id"): mod for mod in catalog["mods"]}
    bootstrap = CNR_MODULE / "payload/assets/bootstrap"
    manifest_path = CNR_MODULE / "module.json"
    host_path = CNR_MODULE / "payload/assets/pv7/host.properties"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    profile = json.loads(PROFILE.read_text(encoding="utf-8"))
    host_text = host_path.read_text(encoding="utf-8")
    payload_files = list(manifest.get("payloadFiles", []))
    report = []

    verified_host = CNR_MODULE / "payload/lib/arm64-v8a/libpv7host.so.gz"
    if verified_host.is_file():
        expected = "0396656c190c8f06fe195c5336453ab4ee56b5e3d4ccfb481af408ef02cd7ae9"
        unpacked = gzip.decompress(verified_host.read_bytes())
        if hashlib.sha256(unpacked).hexdigest() != expected:
            raise ValueError("CNR ARM64 host no longer matches the on-device verified PV7 binary")
        report.append("CNR PV7 ARM64 host: verified cooperative-worker build")

    for name in PINNED_MODS:
        entry = versioned[name]
        version = entry["latestVersion"]
        release = source / name / f"{name}-{version}.dll"
        canonical = source / name / f"{name}.dll"
        if not release.is_file() or not canonical.is_file():
            raise FileNotFoundError(f"Missing verified release for {name} {version}")
        checksum = sha256(release)
        if checksum != sha256(canonical):
            raise ValueError(f"Latest release and canonical DLL differ for {name} {version}")
        target = bootstrap / f"{name}.dll"
        hash_path = bootstrap / f"{name}.dll.sha256"
        if not args.dry_run:
            bootstrap.mkdir(parents=True, exist_ok=True)
            # Never write if already an identical binary.
            if not target.exists() or sha256(target) != checksum:
                target.write_bytes(release.read_bytes())
            hash_path.write_bytes((checksum + "\n").encode("ascii"))
        elif target.exists() and sha256(target) != checksum:
            report.append(f"{name}: payload update required")
        for suffix in (".dll", ".dll.sha256"):
            asset = "assets/bootstrap/" + name + suffix
            if asset not in payload_files:
                payload_files.append(asset)

        index = BOOTSTRAP_INDEX[name]
        if index != 0:
            anchor = f"bootstrap.{index}.asset=bootstrap/{name}.dll"
            if anchor not in host_text:
                host_text += (
                    f"\n# Recommended CNR {name} {version}; seed only when absent, preserving custom installations.\n"
                    f"{anchor}\n"
                    f"bootstrap.{index}.toFilesRelative=CNRMods/{name}.dll\n"
                    f"bootstrap.{index}.sha256Asset=bootstrap/{name}.dll.sha256\n"
                    f"bootstrap.{index}.preserveCustom=true\n"
                )
        report.append(f"{name} {version}: SHA256 {checksum[:16]}")

    manifest["version"] = "1.0.8"
    manifest["dependencies"] = ["core-compat", "unity-4.6-compat"]
    manifest["payloadFiles"] = payload_files
    manifest["description"] = (
        "CNR 3.0.2-specific compatibility including a verified ARM64 PV7 host "
        "with cooperative worker wait, and a pinned managed-mod bootstrap "
        "that preserves custom installs."
    )
    profile["profileVersion"] = max(int(profile.get("profileVersion", 1)), 2)
    profile["recommendedModules"] = ["core-compat", "unity-4.6-compat", "cnr-compat"]
    profile["notes"] = (
        "Recommended CNR 3.0.2 setup: PV7 core plus generic Unity 4.6 policy "
        "and CNR-specific storage/bootstrap compatibility. CNR module seeds current "
        "verified CNRMod, Mod Manager, Settings and Graphics Settings DLLs on first install "
        "without overwriting existing custom mods. Shipment MW passed a limited on-device "
        "spawn/movement/jump smoke test; CPU and thermal optimization is ongoing."
    )
    if not args.dry_run:
        host_path.write_text(host_text, encoding="utf-8")
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        PROFILE.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
    print("\n".join(report))
    print("Mode:", "DRY RUN" if args.dry_run else "UPDATED")
    print("Selected:", ", ".join(profile["recommendedModules"]))

if __name__ == "__main__":
    main()
