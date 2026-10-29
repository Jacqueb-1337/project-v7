#!/usr/bin/env python3
"""Validate the Project V7 compatibility catalog. No third-party dependencies."""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path, PurePosixPath

SECTIONS = ("modules", "profiles", "apps")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=Path("catalog"))
    parser.add_argument("--baseline-index", type=Path, help="Base branch index.json, for PR removal checks")
    parser.add_argument("--allow-removals", type=Path, default=Path(".github/catalog-removal-approvals.json"))
    args = parser.parse_args()
    root = args.catalog.resolve()
    errors, warnings = [], []

    def error(where, detail):
        errors.append(f"{where}: {detail}")

    def read_json(file):
        try:
            return json.loads(file.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError) as exc:
            error(file, f"invalid JSON or missing file: {exc}")
            return None

    index = read_json(root / "index.json")
    if not isinstance(index, dict):
        error("index.json", "must contain a JSON object")
        index = {}
    if index.get("schemaVersion") != 1:
        error("index.schemaVersion", "must be 1")

    paths = {}
    records = {section: [] for section in SECTIONS}
    for section in SECTIONS:
        entries = index.get(section)
        if not isinstance(entries, list) or any(not isinstance(p, str) for p in entries):
            error("index." + section, "expected a list of relative JSON file paths")
            entries = []
        paths[section] = entries
        for path, count in Counter(entries).items():
            if count > 1:
                error("index." + section, f"duplicate index entry: {path}")
        for relative in entries:
            posix = PurePosixPath(relative)
            if (not relative or "\\" in relative or posix.is_absolute()
                    or ".." in posix.parts or relative != posix.as_posix()
                    or not relative.startswith(section + "/") or not relative.endswith(".json")):
                error("index." + section, f"invalid catalog path: {relative!r}")
                continue
            file = (root / relative).resolve()
            if not file.is_relative_to(root) or not file.is_file():
                error("index." + section, f"missing or unsafe file: {relative}")
                continue
            data = read_json(file)
            if isinstance(data, dict):
                records[section].append((relative, data))
            elif data is not None:
                error(relative, "must contain a JSON object")

    # New manifests must be registered in the index; no accidentally nested catalog.
    for section, pattern in (("modules", "**/module.json"), ("profiles", "**/*.json"), ("apps", "*.json")):
        for file in (root / section).glob(pattern):
            relative = file.relative_to(root).as_posix()
            if relative not in paths[section]:
                error(relative, f"not registered in index.{section}")
    if (root / "catalog").exists():
        error("catalog/catalog", "unexpected extra catalog folder")

    module_ids = {}
    dependencies = {}
    for path, module in records["modules"]:
        ident = module.get("id")
        if not isinstance(ident, str) or not ident or "/" in ident:
            error(path, "module.id must be a simple nonempty ID, not a path")
            continue
        if ident in module_ids:
            error(path, f"duplicate module ID {ident!r} (also in {module_ids[ident]})")
        module_ids[ident] = path
        if module.get("kind") not in ("general", "game"):
            error(path, "module.kind must be general or game")
        for field in ("name", "version", "description"):
            if not isinstance(module.get(field), str) or not module[field].strip():
                error(path, f"module.{field} must be nonempty text")
        if not isinstance(module.get("targets"), dict):
            error(path, "module.targets must be an object")
        ops = module.get("operations")
        if not isinstance(ops, list) or any(not isinstance(o, dict) or not isinstance(o.get("type"), str) for o in ops):
            error(path, "module.operations must be a list of typed objects")
        if module.get("kind") == "game" and ops == [] and not module.get("payloadFiles"):
            warnings.append(f"{path}: game module has no patch operations or payloads")
        deps = module.get("dependencies", [])
        if not isinstance(deps, list) or any(not isinstance(dep, str) for dep in deps):
            error(path, "module.dependencies must be a list of module IDs")
            deps = []
        dependencies[ident] = deps
        payloads = module.get("payloadFiles", [])
        if not isinstance(payloads, list) or any(not isinstance(p, str) for p in payloads):
            error(path, "module.payloadFiles must be a list of paths")
        else:
            for payload in payloads:
                p = PurePosixPath(payload)
                if (not payload or "\\" in payload or p.is_absolute() or ".." in p.parts):
                    error(path, f"invalid payload path: {payload!r}")
                elif not (root / path).parent.joinpath("payload", payload).is_file():
                    error(path, f"missing payload file: payload/{payload}")

    for ident, deps in dependencies.items():
        for dep in deps:
            if dep not in module_ids:
                error(module_ids[ident], f"unknown dependency module ID {dep!r}")
            elif ident == dep:
                error(module_ids[ident], "module cannot depend on itself")

    visited, active = set(), set()
    def walk(ident):
        if ident in active:
            error(module_ids[ident], "circular module dependency detected")
            return
        if ident in visited:
            return
        visited.add(ident)
        active.add(ident)
        for dep in dependencies.get(ident, []):
            if dep in module_ids:
                walk(dep)
        active.remove(ident)

    for ident in module_ids:
        walk(ident)

    seen_profiles = set()
    for path, profile in records["profiles"]:
        ident = profile.get("id")
        if not isinstance(ident, str) or not ident:
            error(path, "profile.id must be nonempty text")
        elif ident in seen_profiles:
            error(path, f"duplicate profile ID {ident!r}")
        else:
            seen_profiles.add(ident)
        if not isinstance(profile.get("packageId"), str) or not profile["packageId"]:
            error(path, "profile.packageId must be nonempty text")
        if "tested" in profile and not isinstance(profile["tested"], bool):
            error(path, "profile.tested must be true or false")
        recommended = profile.get("recommendedModules")
        if not isinstance(recommended, list) or any(not isinstance(v, str) for v in recommended):
            error(path, "profile.recommendedModules must be a list of module IDs")
        else:
            for mod_id in recommended:
                if mod_id not in module_ids:
                    note = " (use module IDs, not module.json paths)" if "/" in mod_id else ""
                    error(path, f"unknown recommended module {mod_id!r}{note}")
        blacklist = profile.get("blacklist")
        if not isinstance(blacklist, list):
            error(path, "profile.blacklist must be a list")
        else:
            for rule in blacklist:
                if not isinstance(rule, dict) or not isinstance(rule.get("moduleId"), str):
                    error(path, "blacklist rules require a moduleId")
                    continue
                conditional = rule.get("whenAnySelected", [])
                if not isinstance(conditional, list):
                    error(path, "blacklist.whenAnySelected must be a list of module IDs")
                    continue
                for mod_id in [rule["moduleId"], *conditional]:
                    if not isinstance(mod_id, str) or mod_id not in module_ids:
                        error(path, f"blacklist references unknown module {mod_id!r}")

    seen_apps = set()
    for path, app in records["apps"]:
        package = app.get("packageName")
        if not isinstance(package, str) or not package:
            error(path, "app.packageName must be nonempty text")
        elif package in seen_apps:
            error(path, f"duplicate app package {package!r}")
        else:
            seen_apps.add(package)
        if not isinstance(app.get("displayName"), str) or not app["displayName"]:
            error(path, "app.displayName must be nonempty text")
        for field in ("downloadUrl", "iconUrl"):
            value = app.get(field)
            if value is not None and (not isinstance(value, str)
                                     or any(token in value.upper() for token in ("YOUR_USERNAME", "YOUR_REPO", "REPLACE_ME", "EXAMPLE.COM"))):
                error(path, f"{field} has a placeholder or invalid value")

    # PR-only guard: every removal must be explicitly justified in a reviewed file.
    if args.baseline_index:
        base = read_json(args.baseline_index)
        if isinstance(base, dict):
            approvals = read_json(args.allow_removals) if args.allow_removals.is_file() else {}
            if not isinstance(approvals, dict):
                error(args.allow_removals, "expected an object mapping removed paths to reasons")
                approvals = {}
            removed_all = set()
            for section in SECTIONS:
                old = base.get(section, [])
                if not isinstance(old, list):
                    error(args.baseline_index, f"index.{section} must be a list")
                    continue
                for removed in set(old) - set(paths[section]):
                    removed_all.add(removed)
                    reason = approvals.get(removed)
                    if not isinstance(reason, str) or not reason.strip():
                        error("index." + section, f"removed {removed!r} without explanation in {args.allow_removals}")
            for path in approvals:
                if path not in removed_all:
                    error(args.allow_removals, f"stale/unnecessary removal approval: {path!r}")

    for item in warnings:
        print("WARNING:", item)
    for item in errors:
        print("ERROR:", item, file=sys.stderr)
    if errors:
        print(f"Catalog validation failed: {len(errors)} error(s), {len(warnings)} warning(s)", file=sys.stderr)
        return 1
    print(f"Catalog OK: {len(module_ids)} modules, {len(records['profiles'])} profiles, "
          f"{len(records['apps'])} apps; {len(warnings)} warning(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
