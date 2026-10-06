# Registro de decisiones

Formato: fecha · decisión · motivo · alternativas descartadas · cómo revertirla.

## 2026-10-06 · Bootstrap del repo en local, sin push a `main`
- **Decisión:** `main` se crea solo en local (commit inicial con `docs/` y `CLAUDE.md` copiado de `docs/mapas-CLAUDE.md`). No se empuja nada hasta que el usuario cree `main` en el remoto y configure CI y protección de rama.
- **Motivo:** el remoto `origin` está vacío (sin `main`), así que no se pueden abrir PR. Empujar a `main` está prohibido por CLAUDE.md y crear `.github/workflows/` o la protección de rama es caso de «Cuándo preguntar» nº 5.
- **Descartado:** empujar un commit inicial a `main` (viola el flujo); empujar una rama de spike (GitHub la haría rama por defecto).
- **Revertir:** `git push origin main` tras acordarlo con el usuario; las ramas `spike/*` locales se pueden empujar y abrir como PR a partir de ese momento.

## 2026-10-06 · No se instala `.claude/settings.json` ni `.github/workflows/ci.yml`
- **Decisión:** `mapas-claude-settings.json` y `mapas-ci.yml` quedan sin tocar en la raíz.
- **Motivo:** el primero cambia mis propios permisos y el segundo es un workflow (CLAUDE.md, «Cuándo preguntar» nº 5).
- **Revertir:** copiarlos a `.claude/settings.json` y `.github/workflows/ci.yml` (paso 2 de `docs/mapas-README.md`).

## 2026-10-06 · El código fuente de CoMaps vive fuera del repo
- **Decisión:** se clona en `~/repos/comaps-spike/` (fuera del repo), fijado a un tag estable; el repo solo guarda scripts y resultados en `spike/` y `docs/spike/`.
- **Motivo:** instrucción explícita del usuario; evita mezclar Apache-2.0 con el código propio antes de decidir.
- **Descartado:** submódulo (válido, pero añade ruido a un spike descartable).
- **Revertir:** `git submodule add` más adelante si se elige A o C.

## 2026-10-06 · Dispositivos y concurrencia en el spike
- **Dispositivo disponible:** un único Pixel 8 (Android 17, SDK 37, arm64, 120 Hz, con GMS). Cubre solo la clase «gama alta con GMS».
- **Decisión:** el acceso al dispositivo se serializa con `flock`; compilaciones C++ limitadas a `-j6` por la RAM libre (~5 GB de 30 GB al empezar).
- **Consecuencia:** gama media/baja, ROM china sin GMS y de-Googled quedan «no medido». Sin esas clases no se puede decidir el criterio «Sin GMS» ni los umbrales de gama media.
