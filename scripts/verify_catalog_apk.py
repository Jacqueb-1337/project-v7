"""Verify metadata-only catalog packaging without removing the generic host."""
import json
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as apk:
    names = set(apk.namelist())
    index = json.loads(apk.read("assets/index.json").decode("utf-8-sig"))
    for key in ("modules", "profiles", "apps"):
        for path in index[key]:
            assert "assets/" + path in names, path
    payloads = [n for n in names if n.startswith("assets/modules/") and "/payload/" in n]
    assert not payloads, payloads
    assert "assets/pv7/templates/pv7-host-template.apk" in names, "Generic host template absent"
print("Verified metadata-only catalog and preserved generic host template.")
