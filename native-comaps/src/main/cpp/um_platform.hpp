#pragma once

#include <string>

namespace um
{
// Initializes the headless Platform (no Java Context): dirs, threads and own "Gui thread".
void InitAndroidPlatform(std::string const & apkPath, std::string const & writableDir, std::string const & tmpDir);
}  // namespace um
