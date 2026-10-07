// C++ facade over the CoMaps core WITHOUT Framework, WITHOUT drape and WITHOUT bookmarks:
// DataSource + search::Engine + routing::IndexRouter. No JNI here (see um_jni.cpp).
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

// Avoid bits (same order as Kotlin's RouteOptions).
enum AvoidFlags : int32_t
{
  kAvoidMotorway = 1 << 0,
  kAvoidToll = 1 << 1,
  kAvoidFerry = 1 << 2,
  kAvoidUnpaved = 1 << 3,
};

// Bike cycle-infrastructure level (BikeCycleways in Kotlin), 2 bits of the same flags integer: 0 = off, 1 = prefer,
// 2 = strongly prefer, 3 = only. Only meaningful for the bike profile (see native-comaps/patches).
constexpr int32_t kCycleLevelShift = 4;
constexpr int32_t kCycleLevelMask = 3 << kCycleLevelShift;
constexpr int32_t kCycleLevelOnly = 3;

// Own result code (not a routing::RouterResultCode): "Only cycle infrastructure" found no route. Same value as
// RouteCode.NO_CYCLE_ROUTE in Kotlin.
constexpr int32_t kRouteNoCycleRoute = 1004;

struct RouteOut
{
  // routing::RouterResultCode as an integer (0 = NoError, 8 = RouteNotFound, 9 = NeedMoreMaps, ...).
  int32_t code = 10;
  std::vector<double> latLon;  // lat0, lon0, lat1, lon1, ...
  double distanceMeters = 0;
  double durationSeconds = 0;

  // Guidance (only if requested): empty = not requested. Format in docs/phase2/maneuvers.md and in `GuidanceWire` (Kotlin):
  //   [version, nManeuvers, nLimits,
  //    per maneuver: geometryIndex, turn(WireTurn), roundaboutExit(-1 = no), nameIndex(-1 = no), nLanes,
  //                  per lane: laneWayMask, recommended(0/1),
  //    per limit: from, to, kmh(-1 = no data)]
  // Everything is an integer, exact in double. `guidanceNames` is the street table that nameIndex points to.
  std::vector<double> guidance;
  std::vector<std::string> guidanceNames;
};

// Wire turn codes; same order and values as `TurnType` of :core-routing (Kotlin translates them with a `when`).
enum WireTurn : int32_t
{
  kTurnDepart = 0, kTurnStraight, kTurnSlightRight, kTurnRight, kTurnSharpRight, kTurnSlightLeft, kTurnLeft,
  kTurnSharpLeft, kTurnUTurnLeft, kTurnUTurnRight, kTurnRoundaboutEnter, kTurnRoundaboutLeave, kTurnExitLeft,
  kTurnExitRight, kTurnMerge, kTurnArrive, kTurnArriveLeft, kTurnArriveRight,
};
constexpr int32_t kGuidanceWireVersion = 1;

struct InitParams
{
  std::string resourcesApk;  // APK path (assets/ with classificator, categories, countries.txt...)
  std::string writableDir;   // where the .mwm files live (<dir>/<version>/<Country>.mwm)
  std::string tmpDir;
  std::string locale;        // e.g. "es"
};

class Core
{
public:
  static Core & Instance();

  // Returns "" if all went well, or the error message.
  std::string Init(InitParams const & params);
  bool IsInitialized() const;

  // Rescans writableDir and registers the new mwm files; rebuilds the routers. Returns how many maps there are.
  int RefreshMaps();

  std::vector<SearchHit> Search(std::string const & query, bool hasPos, double lat, double lon, int limit,
                                int timeoutMs, std::string const & locale, bool categorial = false);

  // withGuidance = false leaves the route exactly as before (no extra cost).
  RouteOut Route(Profile profile, std::vector<double> const & latLonPoints, int32_t avoidFlags, int timeoutSec,
                 bool withGuidance = false);

  void Shutdown();

private:
  Core();
  ~Core();
  struct Impl;
  Impl * m_impl;
};
}  // namespace um
