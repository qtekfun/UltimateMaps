// JNI of com.qtekfun.mapas.nativecomaps.NativeCore. It only translates types; the logic is in um_core.cpp.
#include "um_core.hpp"

#include <jni.h>

#include <cstdint>
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

// Strings from the map data (names, OSM tags) are arbitrary UTF-8: NewStringUTF expects *modified* UTF-8 and aborts
// under CheckJNI on invalid bytes. Decode ourselves, replace anything malformed with U+FFFD, and build the Java string
// from UTF-16 (4-byte sequences become surrogate pairs).
jstring SafeJString(JNIEnv * env, std::string const & s)
{
  std::u16string out;
  out.reserve(s.size());
  size_t i = 0;
  while (i < s.size())
  {
    unsigned char c = static_cast<unsigned char>(s[i]);
    uint32_t cp = 0xFFFD;
    size_t len = 1;
    if (c < 0x80)
    {
      cp = c;
    }
    else
    {
      size_t need = (c >= 0xF0 && c <= 0xF4) ? 3 : (c >= 0xE0) ? 2 : (c >= 0xC2 && c < 0xE0) ? 1 : 0;
      if (need > 0 && c < 0xF5)
      {
        uint32_t v = c & (0x3F >> need);
        bool ok = true;
        for (size_t k = 1; k <= need; ++k)
        {
          if (i + k >= s.size() || (static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80)
          {
            ok = false;
            break;
          }
          v = (v << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
        }
        bool overlong = (need == 2 && v < 0x800) || (need == 3 && v < 0x10000);
        if (ok && !overlong && v <= 0x10FFFF && !(v >= 0xD800 && v <= 0xDFFF))
        {
          cp = v;
          len = need + 1;
        }
      }
    }
    i += len;
    if (cp >= 0x10000)
    {
      cp -= 0x10000;
      out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
      out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    }
    else
    {
      out.push_back(static_cast<char16_t>(cp));
    }
  }
  return env->NewString(reinterpret_cast<jchar const *>(out.data()), static_cast<jsize>(out.size()));
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
      jstring js = SafeJString(env, s);
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
    jstring js = SafeJString(env, r.guidanceNames[i]);
    env->SetObjectArrayElement(names, static_cast<jsize>(i), js);
    env->DeleteLocalRef(js);
  }
  env->SetObjectArrayElement(out, 2, names);
  env->DeleteLocalRef(names);
  return out;
}
}
