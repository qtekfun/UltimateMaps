// JNI de com.qtekfun.mapas.nativecomaps.NativeCore. Solo traduce tipos; la logica esta en um_core.cpp.
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

// Devuelve 5 cadenas por resultado: nombre, direccion, categoria, lat, lon.
JNIEXPORT jobjectArray JNICALL Java_com_qtekfun_mapas_nativecomaps_NativeCore_nativeSearch(
    JNIEnv * env, jobject, jstring query, jboolean hasPos, jdouble lat, jdouble lon, jint limit, jint timeoutMs,
    jstring locale)
{
  auto const hits =
      um::Core::Instance().Search(ToStd(env, query), hasPos, lat, lon, limit, timeoutMs, ToStd(env, locale));
  jclass strCls = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(hits.size() * 5), strCls, nullptr);
  jsize i = 0;
  for (auto const & h : hits)
  {
    for (std::string const & s : {h.name, h.address, h.category, std::to_string(h.lat), std::to_string(h.lon)})
    {
      jstring js = env->NewStringUTF(s.c_str());
      env->SetObjectArrayElement(arr, i++, js);
      env->DeleteLocalRef(js);
    }
  }
  return arr;
}

// Devuelve [code, distanciaM, duracionS, lat0, lon0, lat1, lon1, ...].
JNIEXPORT jdoubleArray JNICALL Java_com_qtekfun_mapas_nativecomaps_NativeCore_nativeRoute(
    JNIEnv * env, jobject, jint profile, jdoubleArray points, jint avoidFlags, jint timeoutSec)
{
  jsize const n = env->GetArrayLength(points);
  std::vector<double> pts(n);
  env->GetDoubleArrayRegion(points, 0, n, pts.data());
  auto const r = um::Core::Instance().Route(static_cast<um::Profile>(profile), pts, avoidFlags, timeoutSec);

  std::vector<double> flat;
  flat.reserve(3 + r.latLon.size());
  flat.push_back(r.code);
  flat.push_back(r.distanceMeters);
  flat.push_back(r.durationSeconds);
  flat.insert(flat.end(), r.latLon.begin(), r.latLon.end());
  jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(flat.size()));
  env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
  return out;
}
}
