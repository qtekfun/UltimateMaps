#pragma once

namespace um
{
// Installs, for SIGSEGV, SIGBUS, SIGABRT, SIGFPE and SIGILL, a handler that appends a short note to |notePath| and ends the
// process at once with _exit(128 + signal). Used only in the isolated core process (`:core`).
//
// Why: a native abort (a CoMaps CHECK, a bad memory access) in that process is normally reported to the Android system as an
// application crash, which shows the "App keeps stopping" dialog over the user's screen (and counts as a crash of the app,
// twice when the client retries). The main process does not depend on :core, so the right outcome is a quiet death that the
// client already handles (it restarts the core once, then reports a failed route). The note keeps what the system report
// would have had: the signal, the fault address and the addresses of the stack frames as `module+offset`, which can be
// symbolised with the unstripped library. It never contains a message, a position or a name.
//
// The handler uses only async-signal-safe calls apart from dladdr (best effort); an alarm(3) ends the process if it hangs.
// Safe to call more than once; only the first call counts.
void InstallQuietCrashExit(char const * notePath);
}  // namespace um
