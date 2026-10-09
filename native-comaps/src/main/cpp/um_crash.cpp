#include "um_crash.hpp"

#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#include <cstddef>
#include <cstdint>
#include <initializer_list>

namespace
{
constexpr size_t kPathMax = 512;
constexpr int kMaxFrames = 24;
constexpr size_t kBufSize = 4096;

char g_path[kPathMax];
bool g_installed = false;
volatile sig_atomic_t g_inHandler = 0;

// Tiny formatter on a fixed buffer: snprintf is not async-signal-safe.
struct Out
{
  char buf[kBufSize];
  size_t n = 0;

  void Str(char const * s)
  {
    while (*s != '\0' && n + 1 < kBufSize)
      buf[n++] = *s++;
  }
  void Dec(long long v)
  {
    if (v < 0)
    {
      Str("-");
      v = -v;
    }
    char tmp[24];
    int k = 0;
    do
    {
      tmp[k++] = static_cast<char>('0' + v % 10);
      v /= 10;
    } while (v > 0 && k < 23);
    while (k > 0 && n + 1 < kBufSize)
      buf[n++] = tmp[--k];
  }
  void Hex(uintptr_t v)
  {
    Str("0x");
    char tmp[20];
    int k = 0;
    do
    {
      tmp[k++] = "0123456789abcdef"[v & 0xF];
      v >>= 4;
    } while (v > 0 && k < 19);
    while (k > 0 && n + 1 < kBufSize)
      buf[n++] = tmp[--k];
  }
};

struct Trace
{
  uintptr_t frames[kMaxFrames];
  int count = 0;
};

_Unwind_Reason_Code CollectFrame(_Unwind_Context * ctx, void * arg)
{
  auto * t = static_cast<Trace *>(arg);
  uintptr_t const pc = _Unwind_GetIP(ctx);
  if (pc != 0 && t->count < kMaxFrames)
    t->frames[t->count++] = pc;
  return t->count < kMaxFrames ? _URC_NO_REASON : _URC_END_OF_STACK;
}

char const * Basename(char const * p)
{
  char const * slash = strrchr(p, '/');
  return slash != nullptr ? slash + 1 : p;
}

void Handler(int sig, siginfo_t * info, void *)
{
  // A second fault while handling the first: nothing more to do than leave.
  if (g_inHandler != 0)
    _exit(128 + sig);
  g_inHandler = 1;
  // If anything below blocks (dladdr takes the linker lock, which the crashing thread may hold), SIGALRM's default action
  // ends the process: still no crash dialog, since SIGALRM is not a crash signal.
  alarm(3);

  Out out;
  out.Str("epoch=");
  out.Dec(static_cast<long long>(time(nullptr)));
  out.Str(" native signal=");
  out.Dec(sig);
  if (info != nullptr)
  {
    out.Str(" code=");
    out.Dec(info->si_code);
    out.Str(" fault_addr=");
    out.Hex(reinterpret_cast<uintptr_t>(info->si_addr));
  }
  out.Str("\n");

  Trace trace;
  _Unwind_Backtrace(&CollectFrame, &trace);
  for (int i = 0; i < trace.count; ++i)
  {
    out.Str("  #");
    out.Dec(i);
    out.Str(" ");
    Dl_info di;
    if (dladdr(reinterpret_cast<void *>(trace.frames[i]), &di) != 0 && di.dli_fname != nullptr && di.dli_fbase != nullptr)
    {
      out.Str(Basename(di.dli_fname));
      out.Str("+");
      out.Hex(trace.frames[i] - reinterpret_cast<uintptr_t>(di.dli_fbase));
    }
    else
    {
      out.Str("?+");
      out.Hex(trace.frames[i]);
    }
    out.Str("\n");
  }
  out.Str("\n");

  int const fd = open(g_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
  if (fd >= 0)
  {
    ssize_t const w = write(fd, out.buf, out.n);
    (void)w;
    close(fd);
  }
  _exit(128 + sig);
}
}  // namespace

namespace um
{
void InstallQuietCrashExit(char const * notePath)
{
  if (g_installed || notePath == nullptr || notePath[0] == '\0' || strlen(notePath) >= kPathMax)
    return;
  g_installed = true;
  strncpy(g_path, notePath, kPathMax - 1);
  g_path[kPathMax - 1] = '\0';

  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = &Handler;
  sa.sa_flags = SA_SIGINFO;
  sigemptyset(&sa.sa_mask);
  for (int sig : {SIGSEGV, SIGBUS, SIGABRT, SIGFPE, SIGILL})
    sigaction(sig, &sa, nullptr);
}
}  // namespace um
