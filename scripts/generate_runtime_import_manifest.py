"""Generate advisory Unity ARM32 bridge coverage for a specific host template."""
import argparse
import hashlib
import re
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--linker", type=Path, required=True)
parser.add_argument("--bridge-dir", type=Path, required=True)
parser.add_argument("--template", type=Path, required=True)
parser.add_argument("--source-candidates", action="store_true", help="Include source-only candidates as advisory comments; not binary verified")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
source = args.linker.read_text(encoding="utf-8")
regions = "\n".join(source[source.index("bool " + name + "("):].split("\n    }\n", 1)[0]
                    for name in ("Dispatch", "DispatchClocks", "DispatchZlib"))
for include in ("compat_bridge_methods.inc", "gles_bridge_methods.inc"):
    regions += "\n" + (args.bridge_dir / include).read_text(encoding="utf-8")
names = set(re.findall(r'\b(?:fast_name|name)\s*==\s*"([^"]+)"', regions))
with zipfile.ZipFile(args.template) as apk:
    native = apk.read("lib/arm64-v8a/libpv7host.so")
source_only = sorted(name for name in names if name.encode() + b"\0" not in native)
names = sorted(names - set(source_only))
if not names:
    raise SystemExit("No bridge names found; refusing empty manifest")
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text("# Unity ARM32 static handler candidates; not a semantic guarantee\n"
                       "# ARM64 host SHA256 " + hashlib.sha256(native).hexdigest() + "\n"
                       + "\n".join(names) + "\n"
                       + ("".join("# source-only " + name + "\n" for name in source_only) if args.source_candidates else ""), encoding="utf-8")
print(f"Wrote {len(names)} handler candidates to {args.output}")
