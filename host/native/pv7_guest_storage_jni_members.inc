    // Generic JNI File objects for guest paths. Handles never expose arbitrary
    // host files. Original guest package comes from PV7 compat.properties.
    std::unordered_map<std::uint32_t, std::string> pv7_guest_file_objects_;
    std::uint32_t pv7_next_guest_file_ = 0x0e000000u;

    std::string Pv7SharedRoot() const {
        const auto marker = managed_dir_.find("/files/");
        if (marker == std::string::npos) return {};
        return managed_dir_.substr(0, marker + 6u) + "/GuestSharedStorage";
    }

    std::string Pv7PathForCategory(const std::string& area) const {
        if (area != "obb" && area != "data" &&
            area != "cache" && area != "files") return {};
        const auto root = Pv7SharedRoot();
        return root.empty() ? std::string{} : root + "/" + area;
    }

    std::uint32_t Pv7FileRef(const std::string& path) {
        if (path.empty() || pv7_next_guest_file_ >= 0x0efffffc) return 0u;
        const auto handle = pv7_next_guest_file_;
        pv7_next_guest_file_ += 4;
        pv7_guest_file_objects_.emplace(handle, path);
        return handle;
    }

    // Select a real, package-matched OBB. Do not return invented paths.
    // A game with multiple main files gets the highest version available.
    std::string Pv7MainExpansionFile() const {
        const auto* package = pv7::compat::GuestPackageName();
        const auto folder = Pv7PathForCategory("obb");
        if (!package || !*package || folder.empty()) return {};
        const std::string prefix = "main.";
        const std::string suffix = std::string(".") + package + ".obb";
        std::error_code ec;
        std::string selected;
        std::uint64_t selected_version = 0;
        std::uint64_t required_version = 0;
        std::string configured_version;
        if (pv7::compat::GetSystemProperty("pv7.guestVersionCode",configured_version) &&
            !configured_version.empty() && configured_version.size()<=10u &&
            configured_version.find_first_not_of("0123456789")==std::string::npos) {
            for(char d:configured_version)
                required_version=required_version*10u+static_cast<unsigned>(d-'0');
            if(required_version>2147483647u) required_version=0;
        }
        for (std::filesystem::directory_iterator it(folder, ec), end;
             !ec && it != end; it.increment(ec)) {
            if (!it->is_regular_file(ec) || ec) break;
            const std::string filename = it->path().filename().string();
            if (filename.size() <= prefix.size()+suffix.size() ||
                filename.rfind(prefix,0) != 0 ||
                filename.compare(filename.size()-suffix.size(),suffix.size(),suffix) != 0)
                continue;
            const auto version = filename.substr(
                prefix.size(), filename.size()-prefix.size()-suffix.size());
            if (version.empty() || version.size()>10 ||
                version.find_first_not_of("0123456789") != std::string::npos) continue;
            std::uint64_t numeric = 0;
            for (char digit : version) numeric = numeric*10u+static_cast<unsigned>(digit-'0');
            if (numeric>2147483647u || numeric==0 ||
                (required_version && numeric!=required_version) ||
                numeric<selected_version) continue;
            const auto canonical = std::filesystem::weakly_canonical(it->path(),ec);
            if (ec) break;
            const auto canonical_root = std::filesystem::weakly_canonical(folder,ec);
            if (ec || canonical.parent_path()!=canonical_root) break;
            selected = canonical.string();
            selected_version = numeric;
        }
        return selected;
    }

    bool Pv7GuestStorageObjectMethod(const std::string& method,
                                     const std::string& signature,
                                     std::uint32_t object,
                                     std::uint32_t& result) {
        if ((method == "getObbDir" && signature == "()Ljava/io/File;") ||
            (method == "getFilesDir" && signature == "()Ljava/io/File;") ||
            (method == "getCacheDir" && signature == "()Ljava/io/File;") ||
            (method == "getDataDir" && signature == "()Ljava/io/File;") ||
            (method == "getExternalFilesDir" &&
             signature == "(Ljava/lang/String;)Ljava/io/File;") ||
            (method == "getExternalCacheDir" && signature == "()Ljava/io/File;")) {
            const auto area = method=="getObbDir" ? "obb" :
                (method=="getCacheDir" || method=="getExternalCacheDir") ? "cache" :
                method=="getDataDir" ? "data" : "files";
            const auto path=Pv7PathForCategory(area);
            if (path.empty()) return false;
            result=Pv7FileRef(path);
            return true;
        }
        const auto file = pv7_guest_file_objects_.find(object);
        if (file == pv7_guest_file_objects_.end()) return false;
        if ((method=="getAbsolutePath" || method=="getCanonicalPath" ||
             method=="getPath" || method=="toString") &&
             signature=="()Ljava/lang/String;") {
            result=NewJniString(file->second);
            return true;
        }
        if (method=="getName" && signature=="()Ljava/lang/String;") {
            result=NewJniString(std::filesystem::path(file->second).filename().string());
            return true;
        }
        if (method=="getParentFile" && signature=="()Ljava/io/File;") {
            result=Pv7FileRef(std::filesystem::path(file->second).parent_path().string());
            return true;
        }
        return false;
    }

    bool Pv7GuestStorageStaticMethod(const std::string& method,
                                     const std::string& signature,
                                     std::uint32_t& result) {
        if (signature != "()Ljava/lang/String;") return false;
        // Generic policy for the widespread expansion-file path helper shape.
        // Match capability and signature, not the game package/class name.
        if ((method.find("ExpansionFile") != std::string::npos ||
             method.find("OBBFile") != std::string::npos ||
             method.find("ObbFile") != std::string::npos) &&
            method.rfind("get",0)==0) {
            result=NewJniString(Pv7MainExpansionFile());
            return true;
        }
        if (method=="getObbDir" || method=="getObbDirectory") {
            result=NewJniString(Pv7PathForCategory("obb"));
            return true;
        }
        return false;
    }

    bool Pv7GuestStorageFileBoolean(const std::string& method,
                                    std::uint32_t object,
                                    std::uint32_t& result) const {
        const auto file=pv7_guest_file_objects_.find(object);
        if(file==pv7_guest_file_objects_.end()) return false;
        std::error_code ec;
        const auto path=std::filesystem::path(file->second);
        if(method=="exists") result=std::filesystem::exists(path,ec) && !ec;
        else if(method=="isFile") result=std::filesystem::is_regular_file(path,ec) && !ec;
        else if(method=="isDirectory") result=std::filesystem::is_directory(path,ec) && !ec;
        else return false;
        return true;
    }