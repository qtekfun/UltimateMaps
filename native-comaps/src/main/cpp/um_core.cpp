#include "um_core.hpp"

#include <android/log.h>

#include "um_platform.hpp"

#include "routing/checkpoints.hpp"
#include "routing/index_router.hpp"
#include "routing/route.hpp"
#include "routing/router_delegate.hpp"
#include "routing/routing_callbacks.hpp"
#include "routing/routing_options.hpp"
#include "routing/vehicle_mask.hpp"

#include "routing_common/bicycle_model.hpp"
#include "routing_common/num_mwm_id.hpp"

#include "search/engine.hpp"
#include "search/result.hpp"
#include "search/search_params.hpp"

#include "storage/country_info_getter.hpp"
#include "storage/routing_helpers.hpp"
#include "storage/storage.hpp"

#include "traffic/traffic_cache.hpp"

#include "indexer/categories_holder.hpp"
#include "indexer/classificator_loader.hpp"
#include "indexer/map_style_reader.hpp"
#include "indexer/data_source.hpp"
#include "indexer/feature.hpp"
#include "indexer/feature_data.hpp"
#include "indexer/feature_meta.hpp"
#include "indexer/feature_utils.hpp"

#include "platform/local_country_file.hpp"
#include "platform/local_country_file_utils.hpp"
#include "platform/platform.hpp"
#include "platform/preferred_languages.hpp"

#include "geometry/mercator.hpp"

#include "base/exception.hpp"
#include "base/logging.hpp"
#include "base/string_utils.hpp"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <unordered_map>

// CoMaps writes its logs and its CHECK/ASSERT through its own functions; on Android they have to be hooked up to logcat or a
// core failure is lost silently (the process aborts without a message). Tag: UMCORE. No user locations.
namespace
{
android_LogPriority ToAndroid(base::LogLevel level)
{
  switch (level)
  {
  case base::LDEBUG: return ANDROID_LOG_DEBUG;
  case base::LINFO: return ANDROID_LOG_INFO;
  case base::LWARNING: return ANDROID_LOG_WARN;
  case base::LERROR: return ANDROID_LOG_ERROR;
  default: return ANDROID_LOG_FATAL;
  }
}

void ForwardLog(base::LogLevel level, base::SrcPoint const & src, std::string const & msg)
{
  __android_log_print(ToAndroid(level), "UMCORE", "%s: %s", DebugPrint(src).c_str(), msg.c_str());
}

bool ForwardAssert(base::SrcPoint const & src, std::string const & msg)
{
  __android_log_print(ANDROID_LOG_FATAL, "UMCORE", "ASSERT/CHECK failed at %s: %s", DebugPrint(src).c_str(), msg.c_str());
  return true;  // keep aborting: a failed CHECK leaves the core in an unknown state
}

void InstallDiagnostics()
{
  static bool done = false;
  if (done)
    return;
  done = true;
  base::SetLogMessageFn(&ForwardLog);
  base::SetAssertFunction(&ForwardAssert);
}
}  // namespace

namespace um
{
namespace
{
routing::VehicleType ToVehicle(Profile p)
{
  switch (p)
  {
  case kFoot: return routing::VehicleType::Pedestrian;
  case kBike: return routing::VehicleType::Bicycle;
  case kCar:
  default: return routing::VehicleType::Car;
  }
}

routing::RoutingOptions::OptionType ToOptionMask(Profile p, int32_t flags)
{
  using O = routing::RoutingOptions;
  O::OptionType mask = 0;
  // Motorways and tolls only exist in the vehicle profile (kVehicleOptionsMask).
  if (p == kCar)
  {
    if (flags & kAvoidMotorway)
      mask |= O::Motorway;
    if (flags & kAvoidToll)
      mask |= O::Toll;
  }
  if (flags & kAvoidFerry)
    mask |= O::Ferry;
  if (flags & kAvoidUnpaved)
    mask |= O::Dirty;  // "Dirty" = unpaved; "Paved" is the opposite option
  return mask;
}

// Translates CarDirection (car and bike: IndexRouter uses CarDirectionsEngine for Bicycle) and PedestrianDirection (on foot)
// to the wire turn. Returns -1 if there is no maneuver to show (None, StayOnRoundAbout).
int32_t WireTurnOf(routing::turns::TurnItem const & t)
{
  using routing::turns::CarDirection;
  using routing::turns::PedestrianDirection;
  switch (t.m_turn)
  {
  case CarDirection::GoStraight: return kTurnStraight;
  case CarDirection::TurnRight: return kTurnRight;
  case CarDirection::TurnSharpRight: return kTurnSharpRight;
  case CarDirection::TurnSlightRight: return kTurnSlightRight;
  case CarDirection::TurnLeft: return kTurnLeft;
  case CarDirection::TurnSharpLeft: return kTurnSharpLeft;
  case CarDirection::TurnSlightLeft: return kTurnSlightLeft;
  case CarDirection::UTurnLeft: return kTurnUTurnLeft;
  case CarDirection::UTurnRight: return kTurnUTurnRight;
  case CarDirection::EnterRoundAbout: return kTurnRoundaboutEnter;
  case CarDirection::LeaveRoundAbout: return kTurnRoundaboutLeave;
  case CarDirection::StartAtEndOfStreet: return kTurnDepart;
  case CarDirection::ReachedYourDestination: return kTurnArrive;
  case CarDirection::ExitHighwayToLeft: return kTurnExitLeft;
  case CarDirection::ExitHighwayToRight: return kTurnExitRight;
  case CarDirection::None:
  case CarDirection::StayOnRoundAbout:  // FixupCarTurns already removes it; ignored just in case
  default: break;
  }
  switch (t.m_pedestrianTurn)
  {
  case PedestrianDirection::GoStraight: return kTurnStraight;
  case PedestrianDirection::TurnRight: return kTurnRight;
  case PedestrianDirection::TurnLeft: return kTurnLeft;
  case PedestrianDirection::ReachedYourDestination: return kTurnArrive;
  default: return -1;
  }
}

// Fills out.guidance / out.guidanceNames from route.GetRouteSegments(). The geometry indices are those of
// route.GetPoly(): there is one more point than segments and the maneuver of segment i falls on point i+1 (TurnItem::m_index).
void FillGuidance(routing::Route const & route, RouteOut & out)
{
  auto const & segs = route.GetRouteSegments();
  size_t const pointCount = route.GetPoly().GetSize();
  if (segs.empty() || pointCount < 2)
    return;

  std::vector<double> maneuvers;
  size_t nMan = 0;
  std::unordered_map<std::string, int32_t> nameIdx;
  for (auto const & s : segs)
  {
    auto const & t = s.GetTurn();
    int32_t const wire = WireTurnOf(t);
    if (wire < 0 || t.m_index >= pointCount)
      continue;

    int32_t nameId = -1;
    if (t.m_index < segs.size())
    {
      routing::RouteSegment::RoadNameInfo rni;
      route.GetClosestStreetNameAfterIdx(t.m_index, rni);
      std::string const & name = !rni.m_name.empty() ? rni.m_name : !rni.m_ref.empty() ? rni.m_ref : rni.m_destination;
      if (!name.empty())
      {
        auto const ins = nameIdx.emplace(name, static_cast<int32_t>(out.guidanceNames.size()));
        if (ins.second)
          out.guidanceNames.push_back(name);
        nameId = ins.first->second;
      }
    }

    bool const roundabout = wire == kTurnRoundaboutEnter || wire == kTurnRoundaboutLeave;
    maneuvers.push_back(t.m_index);
    maneuvers.push_back(wire);
    // NOTE: `m_exitNum` is uint32_t; in a ternary with `-1` C++ promotes everything to uint32_t and the -1 arrived as
    // 4294967295 (seen on the Pixel 8: "roundaboutExit is not an integer"). Convert to double BEFORE.
    maneuvers.push_back(roundabout && t.m_exitNum > 0 ? static_cast<double>(t.m_exitNum) : -1.0);
    maneuvers.push_back(nameId);
    maneuvers.push_back(static_cast<double>(t.m_lanes.size()));
    for (auto const & lane : t.m_lanes)
    {
      uint32_t mask = 0;
      for (auto way : lane.laneWays.GetActiveLaneWays())
        mask |= 1u << static_cast<uint32_t>(way);
      maneuvers.push_back(mask);
      maneuvers.push_back(lane.recommendedWay != routing::turns::lanes::LaneWay::None ? 1 : 0);
    }
    ++nMan;
  }

  // Limits: one stretch per run of segments with the same numeric limit (-1 = no data; maxspeed none/walk is not
  // numeric either). Segment i = points [i, i+1]. Only emitted if some segment has data.
  std::vector<double> limits;
  size_t nLim = 0;
  bool anyLimit = false;
  std::vector<int32_t> kmh(segs.size(), -1);
  for (size_t i = 0; i < segs.size(); ++i)
  {
    auto const & sl = segs[i].GetSpeedLimit();
    if (sl.IsValid() && sl.IsNumeric())
    {
      kmh[i] = static_cast<int32_t>(sl.GetSpeedKmPH());
      anyLimit = true;
    }
  }
  if (anyLimit)
  {
    for (size_t i = 0; i < segs.size();)
    {
      size_t j = i;
      while (j + 1 < segs.size() && kmh[j + 1] == kmh[i])
        ++j;
      limits.push_back(static_cast<double>(i));
      limits.push_back(static_cast<double>(j + 1));
      limits.push_back(kmh[i]);
      ++nLim;
      i = j + 1;
    }
  }

  out.guidance.reserve(3 + maneuvers.size() + limits.size());
  out.guidance.push_back(kGuidanceWireVersion);
  out.guidance.push_back(static_cast<double>(nMan));
  out.guidance.push_back(static_cast<double>(nLim));
  out.guidance.insert(out.guidance.end(), maneuvers.begin(), maneuvers.end());
  out.guidance.insert(out.guidance.end(), limits.begin(), limits.end());
}
}  // namespace

struct Core::Impl
{
  std::mutex mu;  // serializes init / refresh / route (there are global routing options in settings)
  bool initialized = false;
  std::string locale = "en";
  std::string engineSpec = "en";  // place locale the search engine's processors were last told about

  FrozenDataSource dataSource;
  std::unique_ptr<storage::Storage> storage;
  std::unique_ptr<storage::CountryInfoGetter> infoGetter;
  std::unique_ptr<search::Engine> engine;
  traffic::TrafficCache trafficCache;
  std::vector<platform::LocalCountryFile> localFiles;
  std::map<routing::VehicleType, std::unique_ptr<routing::IndexRouter>> routers;

  routing::IndexRouter & Router(routing::VehicleType vt)
  {
    auto it = routers.find(vt);
    if (it != routers.end())
      return *it->second;

    auto const countryFileGetter = [this](m2::PointD const & pt) { return infoGetter->GetRegionCountryId(pt); };
    auto const getMwmRect = [this](std::string const & id) -> m2::RectD
    { return infoGetter->GetLimitRectForLeaf(id); };
    auto const parentGetter = [this](std::string const & id) -> std::string { return storage->GetParentIdFor(id); };

    auto numMwmIds = std::make_shared<routing::NumMwmIds>();
    for (auto const & f : localFiles)
    {
      auto const & cf = f.GetCountryFile();
      auto const mwmId = dataSource.GetMwmIdByCountryFile(cf);
      if (mwmId.IsAlive() && storage->IsLeaf(cf.GetName()))
        numMwmIds->RegisterFile(cf);
    }

    bool const loadAltitudes = vt != routing::VehicleType::Car;
    auto router = std::make_unique<routing::IndexRouter>(vt, loadAltitudes, parentGetter, countryFileGetter,
                                                         getMwmRect, numMwmIds,
                                                         routing::MakeNumMwmTree(*numMwmIds, *infoGetter),
                                                         trafficCache, dataSource);
    auto & ref = *router;
    routers.emplace(vt, std::move(router));
    return ref;
  }
};

Core::Core() : m_impl(new Impl) {}
Core::~Core() = default;  // immortal singleton: CoMaps' global state is not destroyed

Core & Core::Instance()
{
  static Core * core = new Core;
  return *core;
}

bool Core::IsInitialized() const
{
  return m_impl->initialized;
}

std::string Core::Init(InitParams const & p)
{
  std::lock_guard<std::mutex> lock(m_impl->mu);
  if (m_impl->initialized)
    return {};
  InstallDiagnostics();
  try
  {
    InitAndroidPlatform(p.resourcesApk, p.writableDir, p.tmpDir);
    auto const place = ParsePlaceLocale(p.locale);
    ApplyPlaceLocale(place);
    m_impl->locale = place.lang;
    m_impl->engineSpec = place.lang + (place.local ? ";local" : "");

    // As CoMaps does: sets the load style and the current one at once. A bare `classificator::Load()` fills the classificator of the
    // load style, but classif() queries that of the current style: if they differ, every type comes out as "Invalid type".
    GetStyleReader().SetCurrentStyle(kDefaultMapStyle);

    m_impl->storage = std::make_unique<storage::Storage>();
    m_impl->infoGetter = storage::CountryInfoReader::CreateCountryInfoGetter(GetPlatform());
    m_impl->infoGetter->SetAffiliations(m_impl->storage->GetAffiliations());

    m_impl->engine = std::make_unique<search::Engine>(
        m_impl->dataSource, GetDefaultCategories(), *m_impl->infoGetter,
        search::Engine::Params(languages::GetTwine(m_impl->locale), 2 /* numThreads */));
    m_impl->initialized = true;
  }
  catch (RootException const & e)
  {
    return std::string("CoMaps init: ") + e.Msg();
  }
  catch (std::exception const & e)
  {
    return std::string("CoMaps init: ") + e.what();
  }
  return {};
}

int Core::RefreshMaps()
{
  std::lock_guard<std::mutex> lock(m_impl->mu);
  if (!m_impl->initialized)
    return 0;

  std::vector<platform::LocalCountryFile> files;
  platform::FindAllLocalMapsAndCleanup(std::numeric_limits<int64_t>::max(), files);
  for (auto & f : files)
    f.SyncWithDisk();

  for (auto const & f : files)
  {
    try
    {
      auto const res = m_impl->dataSource.RegisterMap(f);
      if (res.second != MwmSet::RegResult::Success && res.second != MwmSet::RegResult::VersionAlreadyExists)
        LOG(LWARNING, ("Can't register", f.GetCountryName(), res.second));
    }
    catch (RootException const & e)
    {
      LOG(LERROR, ("Bad map", f.GetCountryName(), e.Msg()));
    }
  }
  m_impl->localFiles = std::move(files);
  m_impl->routers.clear();
  m_impl->engine->ClearCaches();
  m_impl->engine->LoadCitiesBoundaries();
  m_impl->engine->LoadCountriesTree();
  m_impl->engine->CacheWorldLocalities();
  return static_cast<int>(m_impl->localFiles.size());
}

namespace
{
// Fills phone, website, wheelchair and opening hours of |hit| from the feature |id|. Never throws: a map that went away
// or an unreadable feature just leaves the fields empty. Nothing here is logged (no user data).
void ReadPlaceTags(DataSource const & dataSource, FeatureID const & id, SearchHit & hit)
{
  if (!id.IsValid())
    return;
  try
  {
    dataSource.ReadFeature(
        [&hit](FeatureType & ft)
        {
          using feature::Metadata;
          hit.phone = std::string(ft.GetMetadata(Metadata::FMD_PHONE_NUMBER));
          hit.website = std::string(ft.GetMetadata(Metadata::FMD_WEBSITE));
          hit.openingHours = std::string(ft.GetMetadata(Metadata::FMD_OPEN_HOURS));
          // Wheelchair is not stored as metadata: it comes from the feature's classificator types.
          auto const wc = feature::GetWheelchairType(feature::TypesHolder(ft));
          if (wc)
          {
            switch (*wc)
            {
            case ftraits::WheelchairAvailability::Yes: hit.wheelchair = "yes"; break;
            case ftraits::WheelchairAvailability::Limited: hit.wheelchair = "limited"; break;
            case ftraits::WheelchairAvailability::No: hit.wheelchair = "no"; break;
            }
          }
        },
        id);
  }
  catch (RootException const &)
  {
  }
}
}  // namespace

std::vector<SearchHit> Core::Search(std::string const & query, bool hasPos, double lat, double lon, int limit,
                                    int timeoutMs, std::string const & locale, bool categorial)
{
  std::vector<SearchHit> out;
  std::vector<FeatureID> ids;
  if (!m_impl->initialized)
    return out;

  struct State
  {
    std::mutex mu;
    std::condition_variable cv;
    bool done = false;
    std::vector<SearchHit> hits;
    std::vector<FeatureID> ids;  // parallel to hits; metadata is read from these after the search ends
  };
  auto state = std::make_shared<State>();

  search::SearchParams params;
  params.m_query = query;
  // The locale string also carries how place information is localized (see ParsePlaceLocale): the engine reads it while
  // it builds the names, the address and the category of every result.
  auto const place = ParsePlaceLocale(locale.empty() ? m_impl->locale : locale);
  ApplyPlaceLocale(place);
  std::string const spec = place.lang + (place.local ? ";local" : "");
  if (spec != m_impl->engineSpec)
  {
    m_impl->engine->SetLocale(languages::GetTwine(place.lang));
    m_impl->engine->ClearCaches();  // cached locality names were built with the previous language
    m_impl->engineSpec = spec;
  }
  params.m_inputLocale = place.lang;
  params.m_mode = search::Mode::Everywhere;
  params.m_maxNumResults = static_cast<size_t>(std::max(1, limit));
  params.m_batchSize = params.m_maxNumResults;
  params.m_suggestsEnabled = false;
  params.m_needAddress = true;
  params.m_needHighlighting = false;
  // Pure category results (the query is a category name such as "pharmacy"): no matching on feature names.
  params.m_categorialRequest = categorial;
  params.m_timeout = std::chrono::milliseconds(timeoutMs > 0 ? timeoutMs : 8000);
  if (hasPos)
  {
    auto const pt = mercator::FromLatLon(lat, lon);
    params.m_position = pt;
    params.m_viewport = mercator::RectByCenterXYAndSizeInMeters(pt, 20000.0);
  }
  else
  {
    params.m_viewport = mercator::Bounds::FullRect();
  }
  params.m_onResults = [state](search::Results const & results)
  {
    std::lock_guard<std::mutex> lk(state->mu);
    std::vector<SearchHit> hits;
    std::vector<FeatureID> ids;
    for (auto const & r : results)
    {
      if (r.GetResultType() != search::Result::Type::Feature)
        continue;
      SearchHit h;
      h.name = r.GetString();
      h.address = r.GetAddress();
      h.category = r.GetLocalizedFeatureType();
      auto const ll = mercator::ToLatLon(r.GetFeatureCenter());
      h.lat = ll.m_lat;
      h.lon = ll.m_lon;
      hits.push_back(std::move(h));
      ids.push_back(r.GetFeatureID());
    }
    state->hits = std::move(hits);
    state->ids = std::move(ids);
    if (results.IsEndMarker())
      state->done = true;
    state->cv.notify_all();
  };

  auto handle = m_impl->engine->Search(std::move(params));
  {
    std::unique_lock<std::mutex> lk(state->mu);
    // margin over the engine's own timeout
    state->cv.wait_for(lk, std::chrono::milliseconds((timeoutMs > 0 ? timeoutMs : 8000) + 2000),
                       [&] { return state->done; });
    out = state->hits;
    ids = state->ids;
  }
  if (auto h = handle.lock())
    h->Cancel();
  // Metadata only for the hits being returned (at most `limit`), never for the candidates the engine ranked.
  for (size_t i = 0; i < out.size() && i < ids.size(); ++i)
    ReadPlaceTags(m_impl->dataSource, ids[i], out[i]);
  return out;
}

RouteOut Core::Route(Profile profile, std::vector<double> const & pts, int32_t avoidFlags, int timeoutSec,
                     bool withGuidance)
{
  RouteOut out;
  if (!m_impl->initialized || pts.size() < 4 || pts.size() % 2 != 0)
    return out;

  std::lock_guard<std::mutex> lock(m_impl->mu);
  try
  {
    auto const vt = ToVehicle(profile);

    // The avoid options are read by IndexRouter from settings on every route.
    routing::RoutingOptions::SaveOptionsToSettings(
        routing::RoutingOptions(ToOptionMask(profile, avoidFlags), vt));

    // Set on EVERY route (also for the other profiles) so a stale level can never leak; only the bicycle model reads it.
    int32_t const cycleLevel = profile == kBike ? (avoidFlags & kCycleLevelMask) >> kCycleLevelShift : 0;
    routing::SetBicycleCycleInfra(static_cast<routing::BicycleCycleInfra>(cycleLevel));

    std::vector<m2::PointD> mercatorPts;
    for (size_t i = 0; i + 1 < pts.size(); i += 2)
      mercatorPts.push_back(mercator::FromLatLon(pts[i], pts[i + 1]));

    routing::RouterDelegate delegate;
    if (timeoutSec > 0)
      delegate.SetTimeout(static_cast<uint32_t>(timeoutSec));
    routing::Route route("um", 0 /* routeId */);
    auto & router = m_impl->Router(vt);
    auto const code = router.CalculateRoute(routing::Checkpoints(std::move(mercatorPts)), m2::PointD::Zero(),
                                            false /* adjust */, delegate, route);
    router.SetGuides({});
    out.code = static_cast<int32_t>(code);
    if (cycleLevel == kCycleLevelOnly)
    {
      // With "only cycle infrastructure" an endpoint away from a lane is not snappable and cycle networks have gaps.
      using R = routing::RouterResultCode;
      if (code == R::StartPointNotFound || code == R::EndPointNotFound || code == R::RouteNotFound ||
          code == R::IntermediatePointNotFound)
        out.code = kRouteNoCycleRoute;
    }
    if (code == routing::RouterResultCode::NoError || code == routing::RouterResultCode::HasWarnings)
    {
      for (auto const & p : route.GetPoly().GetPoints())
      {
        auto const ll = mercator::ToLatLon(p);
        out.latLon.push_back(ll.m_lat);
        out.latLon.push_back(ll.m_lon);
      }
      out.distanceMeters = route.GetTotalDistanceMeters();
      out.durationSeconds = route.GetTotalTimeSec();
      if (withGuidance)
      {
        try
        {
          FillGuidance(route, out);
        }
        catch (RootException const & e)
        {
          LOG(LERROR, ("Guidance failed:", e.Msg()));
          out.guidance.clear();
          out.guidanceNames.clear();
        }
      }
    }
  }
  catch (RootException const & e)
  {
    LOG(LERROR, ("Route failed:", e.Msg()));
    out.code = static_cast<int32_t>(routing::RouterResultCode::InternalError);
  }
  return out;
}

void Core::Shutdown() {}
}  // namespace um
