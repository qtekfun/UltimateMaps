// Sustituto de libs/mwm_diff/diff.cpp. El original depende de bsdiff-courgette
// (BSD Protection License, incompatible con GPLv3), que NO se compila aqui.
// Efecto: las actualizaciones incrementales por diff no existen; se descarga el mwm completo.
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
