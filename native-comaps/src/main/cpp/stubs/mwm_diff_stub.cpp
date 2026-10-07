// Substitute for libs/mwm_diff/diff.cpp. The original depends on bsdiff-courgette
// (BSD Protection License, incompatible with GPLv3), which is NOT built here.
// Effect: incremental diff updates do not exist; the full mwm is downloaded.
#include "mwm_diff/diff.hpp"

namespace generator::mwm_diff
{
bool MakeDiff(std::string const &, std::string const &, std::string const &)
{
  return false;
}

DiffApplicationResult ApplyDiff(std::string const &, std::string const &, std::string const &,
                                base::Cancellable const &)
{
  return DiffApplicationResult::Failed;
}

std::string DebugPrint(DiffApplicationResult const & result)
{
  switch (result)
  {
  case DiffApplicationResult::Ok: return "Ok";
  case DiffApplicationResult::Failed: return "Failed";
  case DiffApplicationResult::Cancelled: return "Cancelled";
  }
  return "Unknown";
}
}  // namespace generator::mwm_diff
