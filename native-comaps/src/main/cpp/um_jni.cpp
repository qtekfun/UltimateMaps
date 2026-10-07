// JNI of com.qtekfun.mapas.nativecomaps.NativeCore. It only translates types; the logic is in um_core.cpp.
#include "um_core.hpp"

#include <jni.h>

#include <string>
#include <vector>

namespace
{
std::string ToStd(JNIEnv * env, jstring s)
{
  if (s == nullptr)
    return {};
  char const * c = env->GetStringUTFChars(s, nullptr);
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}
}  // namespace

extern "C"
{
JNIEXPORT jstring JNICALL Java_com_qtekfun_mapas_nativecomaps_NativeCore_nativeInit(
    JNIEnv * env, jobject, jstring apk, jstring writable, jstring tmp, jstring locale)
{
  um::InitParams p;
  p.resourcesApk = ToStd(env, apk);
  p.writableDir = ToStd(env, writable);
  p.tmpDir = ToStd(env, tmp);
  p.locale = ToStd(env, locale);
  return env->NewStringUTF(um::Core::Instance().Init(p).c_str());
}

JNIEXPORT jint JNICALL Java_com_qtekfun_mapas_nativecomaps_NativeCore_nativeRefreshMaps(JNIEnv *, jobject)
{
  return um::Core::Instance().RefreshMaps();
}

// Returns kSearchStride strings per result: name, address, category, lat, lon, phone, website, wheelchair, opening hours.
// Keep in sync with SearchWire in CoMapsCore.kt (version 2; version 1 was the first five only).
constexpr jsize kSearchStride = 9;

JNIEXPORT jobjectArray JNICALL Java_com_qtekfun_mapas_nativecomaps_NativeCore_nativeSearch(
    JNIEnv * env, jobject, jstring query, jboolean hasPos, jdouble lat, jdouble lon, jint limit, jint timeoutMs,
    jstring locale, jboolean categorial)
{
  auto const hits = um::Core::Instance().Search(ToStd(env, query), hasPos, lat, lon, limit, timeoutMs, ToStd(env, locale),
                                                categorial == JNI_TRUE);
  jclass strCls = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(hits.size() * kSearchStride), strCls, nullptr);
  jsize i = 0;
  for (auto const & h : hits)
  {
    for (std::string const & s : {h.name, h.address, h.category, std::to_string(h.lat), std::to_string(h.lon), h.phone, h.website,
                                 h.wheelchair, h.openingHours})
    {
      jstring js = env->NewStringUTF(s.c_str());
      env->SetObjectArrayElement(arr, i++, js);
      env->DeleteLocalRef(js);
    }
  }
  return arr;
}

}

namespace
{
jdoubleArray ToJDoubles(JNIEnv * env, std::vector<double> const & v)
{
  jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(v.size()));
  env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(v.size()), v.data());
  return out;
}

std::vector<double> RouteFlat(um::RouteOut const & r)
{
  std::vector<double> flat;
  flat.reserve(3 + r.latLon.size());
  flat.push_back(r.code);
  flat.push_back(r.distanceMeters);
  flat.push_back(r.durationSeconds);
  flat.insert(flat.end(), r.latLon.begin(), r.latLon.end());
  return flat;
}

std::vector<double> ReadDoubles(JNIEnv * env, jdoubleArray a)
{
  jsize const n = env->GetArrayLength(a);
  std::vector<double> v(n);
  env->GetDoubleArrayRegion(a, 0, n, v.data());
  return v;
}
}  // namespace

extern "C"
{
// Returns [code, distanceM, durationS, lat0, lon0, lat1, lon1, ...].
JNIEXPORT jdoubleArray JNICALL Java_com_qtekfun_mapas_nativecomaps_NativeCore_nativeRoute(
    JNIEnv * env, jobject, jint profile, jdoubleArray points, jint avoidFlags, jint timeoutSec)
{
  auto const r =
      um::Core::Instance().Route(static_cast<um::Profile>(profile), ReadDoubles(env, points), avoidFlags, timeoutSec);
  return ToJDoubles(env, RouteFlat(r));
}

// Like nativeRoute but with guidance. Returns Object[3]: { double[] route (same format as nativeRoute),
// double[] guidance (see um_core.hpp; empty if none), String[] street names }.
JNIEXPORT jobjectArray JNICALL Java_com_qtekfun_mapas_nativecomaps_NativeCore_nativeRouteGuidance(
    JNIEnv * env, jobject, jint profile, jdoubleArray points, jint avoidFlags, jint timeoutSec)
{
  auto const r = um::Core::Instance().Route(static_cast<um::Profile>(profile), ReadDoubles(env, points), avoidFlags,
                                            timeoutSec, true /* withGuidance */);
  jclass objCls = env->FindClass("java/lang/Object");
  jclass strCls = env->FindClass("java/lang/String");
  jobjectArray out = env->NewObjectArray(3, objCls, nullptr);

  jdoubleArray route = ToJDoubles(env, RouteFlat(r));
  env->SetObjectArrayElement(out, 0, route);
  env->DeleteLocalRef(route);

  jdoubleArray guidance = ToJDoubles(env, r.guidance);
  env->SetObjectArrayElement(out, 1, guidance);
  env->DeleteLocalRef(guidance);

  jobjectArray names = env->NewObjectArray(static_cast<jsize>(r.guidanceNames.size()), strCls, nullptr);
  for (size_t i = 0; i < r.guidanceNames.size(); ++i)
  {
    jstring js = env->NewStringUTF(r.guidanceNames[i].c_str());
    env->SetObjectArrayElement(names, static_cast<jsize>(i), js);
    env->DeleteLocalRef(js);
  }
  env->SetObjectArrayElement(out, 2, names);
  env->DeleteLocalRef(names);
  return out;
}
}
