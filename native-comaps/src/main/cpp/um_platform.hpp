#pragma once

#include <string>

namespace um
{
// Inicializa la Platform headless (sin Context Java): dirs, hilos y "hilo Gui" propio.
void InitAndroidPlatform(std::string const & apkPath, std::string const & writableDir, std::string const & tmpDir);
}  // namespace um
