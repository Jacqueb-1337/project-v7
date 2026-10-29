"""Offline tests for the Cocos ARM32 ELF analyzer (no game files required)."""
from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest
import zipfile

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from inspect_cocos_apk import elf32_inventory, inspect_apk  # noqa: E402


def fake_elf(machine: int = 40) -> bytes:
    header = bytearray(52)
    header[:4] = b"\x7fELF"
    header[4] = 1
    header[5] = 1
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header)


class InspectCocosApkTest(unittest.TestCase):
    def test_arm32_without_sections(self):
        result = elf32_inventory(fake_elf())
        self.assertEqual("ELF32 ARM little-endian", result["architecture"])
        self.assertEqual([], result["needed"])
        self.assertEqual([], result["cocos_jni_exports"])

    def test_rejects_other_machines(self):
        with self.assertRaisesRegex(ValueError, "EM_ARM"):
            elf32_inventory(fake_elf(machine=62))

    def test_rejects_invalid_file(self):
        with self.assertRaisesRegex(ValueError, "Not an ELF"):
            elf32_inventory(b"not an elf")

    def test_detects_zip_guest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "example.apk"
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("lib/armeabi/libgame.so", fake_elf())
                z.writestr("org/cocos2dx/lib/Cocos2dxRenderer.class", b"fixture")
            result = inspect_apk(path)
            self.assertEqual("lib/armeabi/libgame.so", result["native_library"])
            self.assertTrue(result["cocos_class_tree"])


if __name__ == "__main__":
    unittest.main()
