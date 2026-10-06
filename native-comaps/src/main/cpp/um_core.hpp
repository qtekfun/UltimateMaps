// Fachada C++ sobre el nucleo de CoMaps SIN Framework, SIN drape y SIN bookmarks:
// DataSource + search::Engine + routing::IndexRouter. Sin JNI aqui (ver um_jni.cpp).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace um
{
struct SearchHit
{
  std::string name;
  std::string address;
  std::string category;
  double lat = 0;
  double lon = 0;
};

enum Profile : int32_t { kCar = 0, kFoot = 1, kBike = 2 };

// Bits de evitar (mismo orden que RouteOptions de Kotlin).
enum AvoidFlags : int32_t
{
  kAvoidMotorway = 1 << 0,
  kAvoidToll = 1 << 1,
  kAvoidFerry = 1 << 2,
  kAvoidUnpaved = 1 << 3,
};

struct RouteOut
{
  // routing::RouterResultCode como entero (0 = NoError, 8 = RouteNotFound, 9 = NeedMoreMaps, ...).
  int32_t code = 10;
  std::vector<double> latLon;  // lat0, lon0, lat1, lon1, ...
  double distanceMeters = 0;
  double durationSeconds = 0;
};

struct InitParams
{
  std::string resourcesApk;  // ruta del APK (assets/ con classificator, categories, countries.txt...)
  std::string writableDir;   // donde viven los .mwm (<dir>/<version>/<Pais>.mwm)
  std::string tmpDir;
  std::string locale;        // p. ej. "es"
};

class Core
{
public:
  static Core & Instance();

  // Devuelve "" si todo va bien o el mensaje de error.
  std::string Init(InitParams const & params);
  bool IsInitialized() const;

  // Re-escanea writableDir y registra los mwm nuevos; reconstruye los routers. Devuelve cuantos mapas hay.
  int RefreshMaps();

  std::vector<SearchHit> Search(std::string const & query, bool hasPos, double lat, double lon, int limit,
                                int timeoutMs, std::string const & locale);

  RouteOut Route(Profile profile, std::vector<double> const & latLonPoints, int32_t avoidFlags, int timeoutSec);

  void Shutdown();

private:
  Core();
  ~Core();
  struct Impl;
  Impl * m_impl;
};
}  // namespace um
