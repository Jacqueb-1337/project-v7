#!/usr/bin/env python3
"""Apply PV7's generic JNI guest-storage hooks to the native host source.

Use against a checked-out native CNR/PV7 wrapper source:
    python scripts/integrate_guest_storage_native.py D:/Projects/copsnrobbers/cnr64-wrapper

Touches only shared_guest_linker.cpp and the new isolated .inc file.
No package IDs or OBB versions are hardcoded into the hooks.
"""
from pathlib import Path
import argparse
import shutil

ROOT = Path(__file__).resolve().parents[1]
INCLUDE = ROOT / "host/native/pv7_guest_storage_jni_members.inc"

def insert(source: str, anchor: str, new: str, token: str) -> str:
    if token in source:
        return source
    count = source.count(anchor)
    if count != 1:
        raise RuntimeError(f"Expected one anchor {anchor!r}, got {count}")
    return source.replace(anchor, new + anchor, 1)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("native_wrapper", type=Path)
    args = parser.parse_args()
    cpp = args.native_wrapper / "src/shared_guest_linker.cpp"
    target = args.native_wrapper / "src/pv7_guest_storage_jni_members.inc"
    if not cpp.is_file():
        raise RuntimeError(f"Missing native wrapper source: {cpp}")
    data = cpp.read_text(encoding="utf-8")
    data = insert(data, "#include <fstream>", "#include <filesystem>\n", "#include <filesystem>")
    data = insert(
        data, "    std::string JniStringValue(std::uint32_t handle) const {",
        '    #include "pv7_guest_storage_jni_members.inc"\n\n',
        '#include "pv7_guest_storage_jni_members.inc"'
    )
    data = insert(
        data,
        '            if (method == "getPackageManager")',
        '            std::uint32_t pv7_file_return=0;\n'
        '            if (Pv7GuestStorageObjectMethod(method, JniMethodSignatureFor(method_id),\n'
        '                object, pv7_file_return)) { jit->Regs()[0]=pv7_file_return; return; }\n',
        'Pv7GuestStorageObjectMethod(method, JniMethodSignatureFor(method_id),'
    )
    data = insert(
        data, '            if (owner != jni_method_classes.end() && owner->second == "java/lang/Class"',
        '            std::uint32_t pv7_static_return=0;\n'
        '            if (Pv7GuestStorageStaticMethod(name,JniMethodSignatureFor(jit->Regs()[2]),\n'
        '                                            pv7_static_return)) {\n'
        '                jit->Regs()[0]=pv7_static_return; return;\n'
        '            }\n',
        'Pv7GuestStorageStaticMethod(name,JniMethodSignatureFor('
    )
    data = insert(
        data, '            if (jit->Regs()[1] == kJniSharedPreferencesHandle && method == "contains")',
        '            std::uint32_t pv7_boolean_return=0;\n'
        '            if (Pv7GuestStorageFileBoolean(method,jit->Regs()[1],pv7_boolean_return)) {\n'
        '                jit->Regs()[0]=pv7_boolean_return; return;\n'
        '            }\n',
        'Pv7GuestStorageFileBoolean(method,jit->Regs()[1],'
    )
    data = insert(
        data, '            if (MotionEventForHandle(object_handle)) {',
        '            if (pv7_guest_file_objects_.find(object_handle)!=pv7_guest_file_objects_.end()) {\n'
        '                jit->Regs()[0]=EnsureJniClassHandle("java/io/File");\n'
        '                return;\n'
        '            }\n',
        'pv7_guest_file_objects_.find(object_handle)!=pv7_guest_file_objects_.end()'
    )
    data = insert(
        data, '            if (class_name == "com/unity3d/player/WWW" && method_name == "<init>"',
        '            if (class_name=="java/io/File" && method_name=="<init>" &&\n'
        '                method_signature=="(Ljava/lang/String;)V") {\n'
        '                const auto guest_path=JniStringValue(JniConstructorArg32(swi,0));\n'
        '                std::string mapped;\n'
        '                if (pv7::compat::NormalizeGuestPath(guest_path,managed_dir_,mapped) &&\n'
        '                    mapped.find("/GuestSharedStorage/")!=std::string::npos) {\n'
        '                    jit->Regs()[0]=Pv7FileRef(mapped); return;\n'
        '                }\n'
        '            }\n',
        'class_name=="java/io/File" && method_name=="<init>"'
    )
    # Host->guest string redirection covers generic Java helpers returning
    # absolute legacy paths, even when no explicit method override exists.
    data = insert(
        data, '        jni_strings[handle] = value;',
        '        std::string mapped;\n'
        '        const bool translated=pv7::compat::NormalizeGuestPath(value,managed_dir_,mapped);\n',
        'const bool translated=pv7::compat::NormalizeGuestPath(value,managed_dir_,mapped)'
    )
    data = data.replace(
        '        jni_strings[handle] = value;',
        '        jni_strings[handle] = translated ? mapped : value;',
        1
    ) if '        jni_strings[handle] = value;' in data else data
    # Normalize only trailing newline and preserve legacy Windows line endings
    # only if the input used them, minimizing unrelated source changes.
    original = cpp.read_bytes()
    text = data.replace("\r\n","\n")
    if b"\r\n" in original:
        text = text.replace("\n","\r\n")
    cpp.write_text(text,encoding="utf-8",newline="")
    shutil.copyfile(INCLUDE,target)
    print(f"PV7_JNI_STORAGE_HOOKS_APPLIED {cpp}")
    print(f"PV7_JNI_STORAGE_MEMBERS {target}")

if __name__ == "__main__":
    main()