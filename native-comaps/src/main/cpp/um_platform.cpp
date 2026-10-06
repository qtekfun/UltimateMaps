// Platform minima de Android para el nucleo de CoMaps, sin JNI hacia Java y SIN RED:
// todo el trafico de red lo hace Kotlin a traves de NetworkPolicy (core-net). Aqui los
// clientes HTTP de CoMaps fallan siempre, y la politica de red nativa devuelve "no".
#include "um_platform.hpp"

#include "platform/http_client.hpp"
#include "platform/locale.hpp"
#include "platform/localization.hpp"
#include "platform/localized_types_map.cpp"  // generado por scripts/comaps-prepare.sh
#include "platform/http_thread_callback.hpp"
#include "platform/network_policy.hpp"
#include "platform/platform.hpp"
#include "platform/secure_storage.hpp"

#include "base/thread_pool_delayed.hpp"

#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace
{
class UmPlatform : public Platform
{
public:
  void Init(std::string const & apk, std::string const & writable, std::string const & tmp)
  {
    auto withSlash = [](std::string s)
    {
      if (!s.empty() && s.back() != '/')
        s += '/';
      return s;
    };
    m_resourcesDir = apk;  // ZipFileReader lee "assets/<fichero>" del APK
    m_writableDir = withSlash(writable);
    m_settingsDir = m_writableDir;
    m_tmpDir = withSlash(tmp);
    m_isTablet = false;
    SetGuiThread(std::make_unique<base::DelayedThreadPool>());
  }
};

UmPlatform & Instance()
{
  static UmPlatform platform;
  return platform;
}
}  // namespace

namespace um
{
void InitAndroidPlatform(std::string const & apk, std::string const & writable, std::string const & tmp)
{
  static std::once_flag once;
  std::call_once(once,
                 [&]
  {
    Instance().Init(apk, writable, tmp);
    // Crea los hilos File/Network/Background; vive hasta el fin del proceso.
    static Platform::ThreadRunner runner;
  });
}
}  // namespace um

Platform & GetPlatform()
{
  return Instance();
}

std::string Platform::GetMemoryInfo() const
{
  return {};
}

std::string Platform::DeviceName() const
{
  return "android";
}

std::string Platform::DeviceModel() const
{
  return "android";
}

std::string Platform::Version() const
{
  return "ultimatemaps";
}

int32_t Platform::IntVersion() const
{
  return 0;
}

Platform::EConnectionType Platform::ConnectionStatus()
{
  return EConnectionType::CONNECTION_NONE;
}

Platform::ChargingStatus Platform::GetChargingStatus()
{
  return ChargingStatus::Unknown;
}

uint8_t Platform::GetBatteryLevel()
{
  return 100;
}

namespace platform
{
NetworkPolicy GetCurrentNetworkPolicy()
{
  return NetworkPolicy(false);
}

void SecureStorage::Save(std::string const &, std::string const &) {}
bool SecureStorage::Load(std::string const &, std::string &)
{
  return false;
}
void SecureStorage::Remove(std::string const &) {}

bool HttpClient::RunHttpRequest()
{
  return false;  // sin red nativa
}
}  // namespace platform

class HttpThread
{};

namespace downloader
{
HttpThread * CreateNativeHttpThread(std::string const &, IHttpThreadCallback &, int64_t, int64_t, int64_t,
                                    std::string const &)
{
  return nullptr;
}

void DeleteNativeHttpThread(HttpThread * thread)
{
  delete thread;
}
}  // namespace downloader

// base/thread.cpp pide estos dos ganchos en Android para adjuntar hilos a la JVM. Aqui ningun hilo
// nativo llama a Java, asi que no hacen nada.
void AndroidThreadAttachToJVM() {}
void AndroidThreadDetachFromJVM() {}

// platform/preferred_languages.cpp: idiomas del sistema. El idioma lo decide Kotlin (parametro locale).
std::vector<std::string> GetAndroidSystemLanguages()
{
  return {"en"};
}

namespace platform
{
Locale GetCurrentLocale()
{
  return {"en", "", "", ".", ","};
}

bool GetLocale(std::string, Locale &)
{
  return false;
}

// Nombres legibles de tipos: tabla en ingles generada desde CoMaps (la UI traduce con sus recursos).
std::string GetLocalizedTypeName(std::string const & type)
{
  auto key = "type." + type;
  std::replace(key.begin(), key.end(), '-', '.');
  std::replace(key.begin(), key.end(), ':', '_');
  auto const it = g_type2localizedType.find(key);
  std::string name = (it != g_type2localizedType.end()) ? it->second : std::string();
  return name.empty() ? type : name;
}

std::string GetLocalizedBrandName(std::string const & brand)
{
  return brand;
}

std::string GetLocalizedString(std::string const & key)
{
  return key;
}
}  // namespace platform
