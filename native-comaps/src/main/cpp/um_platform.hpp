#pragma once

#include <string>

namespace um
{
// Initializes the headless Platform (no Java Context): dirs, threads and own "Gui thread".
void InitAndroidPlatform(std::string const & apkPath, std::string const & writableDir, std::string const & tmpDir);

// How place information (names, address regions, categories) is localized. The wire string is the core's existing `locale`
// parameter: "es" = the user's language first; "es;local" = the name as written locally first, the user's language next.
struct PlaceLocale
{
  std::string lang;
  bool local = false;
};
PlaceLocale ParsePlaceLocale(std::string const & spec);
// Idempotent and cheap when nothing changed. Searches are serialized by the caller (Kotlin), so changing it between two
// searches is safe.
void ApplyPlaceLocale(PlaceLocale const & locale);
std::string PlaceLanguage();
}  // namespace um
