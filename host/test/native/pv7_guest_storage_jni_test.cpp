#include <cassert>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <string>
#include <unordered_map>

namespace pv7::compat {
const char* GuestPackageName() { return "org.example.original"; }
std::string version_code;
bool GetSystemProperty(const std::string& key,std::string& value) {
    if(key!="pv7.guestVersionCode" || version_code.empty()) return false;
    value=version_code;return true;
}
}
class GuestJniStorageFixture {
public:
    std::string managed_dir_;
    std::uint32_t nextString=0x1200000u;
    std::unordered_map<std::uint32_t,std::string> strings;
    std::uint32_t NewJniString(const std::string& value) {
        const auto id=nextString;
        nextString+=4u;
        strings.emplace(id,value);
        return id;
    }
#include "../../native/pv7_guest_storage_jni_members.inc"
};
int main() {
    namespace fs=std::filesystem;
    auto base=fs::temp_directory_path()/"pv7-jni-storage-fixture";
    fs::remove_all(base);
    fs::create_directories(base/"files"/"Data"/"Managed");
    auto obb=base/"files"/"GuestSharedStorage"/"obb";
    fs::create_directories(obb);
    auto files=base/"files"/"GuestSharedStorage"/"files";
    fs::create_directories(files);
    GuestJniStorageFixture fixture;
    fixture.managed_dir_=(base/"files"/"Data"/"Managed").generic_string();
    std::uint32_t object=0;
    assert(fixture.Pv7GuestStorageObjectMethod("getObbDir",
        "()Ljava/io/File;",0,object));
    assert(object!=0);
    std::uint32_t pathString=0;
    assert(fixture.Pv7GuestStorageObjectMethod("getAbsolutePath",
        "()Ljava/lang/String;",object,pathString));
    assert(fs::path(fixture.strings.at(pathString))==obb);
    std::uint32_t exists=0;
    assert(fixture.Pv7GuestStorageFileBoolean("exists",object,exists));
    assert(exists==1u);
    assert(fixture.Pv7GuestStorageObjectMethod("getFilesDir",
        "()Ljava/io/File;",0,object));
    assert(fixture.Pv7GuestStorageObjectMethod("getAbsolutePath",
        "()Ljava/lang/String;",object,pathString));
    assert(fs::path(fixture.strings.at(pathString))==files);

    // File discovery never invents missing expansion assets.
    assert(fixture.Pv7MainExpansionFile().empty());
    std::uint32_t returned=0;
    assert(fixture.Pv7GuestStorageStaticMethod("getAPKExpansionFileName",
        "()Ljava/lang/String;",returned));
    assert(fixture.strings.at(returned).empty());
    auto main=obb/"main.16.org.example.original.obb";
    {std::ofstream out(main,std::ios::binary);out<<"OBB";}
    assert(fs::path(fixture.Pv7MainExpansionFile())==main);
    assert(fixture.Pv7GuestStorageStaticMethod("getAPKExpansionFileName",
        "()Ljava/lang/String;",returned));
    assert(fs::path(fixture.strings.at(returned))==main);
    {std::ofstream out(obb/"main.15.org.other.game.obb");out<<"NO";}
    assert(fs::path(fixture.Pv7MainExpansionFile())==main);
    {std::ofstream out(obb/"main.18.org.example.original.obb");out<<"YES";}
    assert(fixture.Pv7MainExpansionFile().find("main.18.")!=std::string::npos);
    pv7::compat::version_code="16";
    assert(fs::path(fixture.Pv7MainExpansionFile())==main);
    pv7::compat::version_code="17";
    assert(fixture.Pv7MainExpansionFile().empty());
    std::uint32_t ignored=0;
    assert(!fixture.Pv7GuestStorageStaticMethod("getScore",
         "()Ljava/lang/String;",ignored));
    fs::remove_all(base);
}