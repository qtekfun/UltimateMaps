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
  // Raw OSM metadata of the feature, read only for the results actually returned; empty when absent.
  std::string phone;
  std::string website;
  std::string wheelchair;    // "yes", "limited" or "no"; empty when untagged
  std::string openingHours;  // the opening_hours text as stored in the map
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

// Route performance switches (debug bench only; the app never sets them, so the default 0 is today's behaviour).
// One integer of bit flags, see docs/phase2/long-routes-perf.md and RoutePerfMode in Kotlin (keep both in sync).
enum PerfFlags : int32_t
{
  kPerfQuietLog = 1 << 0,         // engine log level raised to error while a route is calculated (identical routes)
  kPerfPruneCandidates = 1 << 1,  // skip leaps candidates a lower bound proves worse (identical routes, cars)
  kPerfPersistGraphs = 1 << 2,    // keep the car index graphs between routes (identical routes)
};
// 2 bits: how many distinct first/last transitions the leaps search collects (0 = 15 stock, 1 = 8, 2 = 5, 3 = 3).
// Fewer candidates is faster but may pick a slightly worse route.
constexpr int32_t kPerfCandShift = 3;
constexpr int32_t kPerfCandMask = 3 << kPerfCandShift;
// 2 bits: wall-clock cap on the leaps search once it has a route (0 = 30 s stock, 1 = 10 s, 2 = 5 s, 3 = 2 s).
constexpr int32_t kPerfTimeoutShift = 5;
constexpr int32_t kPerfTimeoutMask = 3 << kPerfTimeoutShift;

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
  //    per limit: from, to, kmh(-1 = no data)
  //    optional, only if the route has tunnels (CoMaps patch 0004): nTunnels, per tunnel: from, to (point indices)]
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

  // Sets the performance switches (PerfFlags + the two 2-bit fields). 0 = stock behaviour. Applies to the next routes.
  void SetPerfMode(int32_t flags);

  // One line "key=value ..." with the timing split of the last route (no coordinates), empty before the first route.
  std::string LastRouteStats();

  void Shutdown();

private:
  Core();
  ~Core();
  struct Impl;
  Impl * m_impl;
};
}  // namespace um
