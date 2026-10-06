# Registro de decisiones

Formato: fecha · decisión · motivo · alternativas descartadas · cómo revertirla.

## 2026-10-06 · Bootstrap del repo en local, sin push a `master`
- **Decisión:** `master` se crea solo en local (commit inicial con `docs/` y `CLAUDE.md` copiado de `docs/mapas-CLAUDE.md`). No se empuja nada hasta que el usuario cree `master` en el remoto y configure CI y protección de rama.
- **Motivo:** el remoto `origin` está vacío (sin `master`), así que no se pueden abrir PR. Empujar a `master` está prohibido por CLAUDE.md y crear `.github/workflows/` o la protección de rama es caso de «Cuándo preguntar» nº 5.
- **Descartado:** empujar un commit inicial a `master` (viola el flujo); empujar una rama de spike (GitHub la haría rama por defecto).
- **Revertir:** `git push origin master` tras acordarlo con el usuario; las ramas `spike/*` locales se pueden empujar y abrir como PR a partir de ese momento.

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

## 2026-10-06 · La rama principal se llama `master`
- **Decisión:** la rama principal es `master` (no `main`), por indicación del usuario. Donde los documentos del paquete dicen `main` (playbook, CI, `settings.json`), léase `master`.
- **Pendiente para el usuario:** el workflow `mapas-ci.yml` (`branches: [main]`), las reglas deny de `mapas-claude-settings.json` (`git push origin main *`) y la protección de rama deben apuntar a `master`.

## 2026-10-06 · Máximo 4 subagentes simultáneos
- **Decisión:** nunca más de 4 subagentes activos a la vez; el flujo MapLibre (d) se lanza cuando termine uno de los cuatro iniciales.
- **Motivo:** instrucción del usuario (y RAM limitada, ~5 GB libres).
- **Revertir:** solo por indicación del usuario.

## 2026-10-06 · Licencias heredadas de CoMaps que bloquean enlazarlo tal cual en una app GPLv3
- **Decisión:** antes de reutilizar código de CoMaps (opciones A/C) hay que excluir o reemplazar `3party/bsdiff-courgette/bsdiff` (BSD Protection License, GPL-incompatible), la fuente `data/fonts/06_code2000.ttf` (shareware) y los iconos Entypo (CC BY-SA 3.0). Se fija «GPLv3 o posterior», nunca GPLv2-only.
- **Motivo:** ver `docs/spike/verificaciones.md` §1.2. Apache-2.0 sí es compatible con GPLv3.
- **Descartado:** asumir que todo `3party/` es permisivo.
- **Revertir:** si el autor de bsdiff o un asesor legal confirma compatibilidad, retirar la exclusión.

## 2026-10-06 · Código independiente del motor: módulos JVM y parsers sin StAX
- **Decisión:** `:core-geo`, `:core-net`, `:core-map`, `:core-search` y `:core-routing` son módulos Kotlin/JVM (`./gradlew test` sin Android); solo `:app` es Android (sabor `foss`, minSdk 26, `applicationId` provisional `com.qtekfun.mapas`). XML (GPX/KML) con `org.xmlpull.v1.XmlPullParser` (plataforma en Android; kXML2 solo en tests JVM), no StAX, porque `javax.xml.stream` no existe en Android. Takeout GeoJSON con kotlinx-serialization-json; CSV con lector propio.
- **Motivo:** tests rápidos y código conservable con cualquier motor (A/B/C). Si el motor elegido exige que `:core-map` sea módulo Android, se convierte entonces.
- **Detalles:** kXML2 acepta ficheros truncados sin error, por lo que los importadores comprueban `depth == 0` al final. Apple `?ll=...&q=Nombre` se interpreta como pin con etiqueta (no como búsqueda). Un `geo:0,0?q=texto` es búsqueda sin sesgo de posición.
- **Descartado:** StAX (no portable a Android); `org.json` (no disponible en JVM puro).

## 2026-10-06 · Motor (A/B/C): sin decisión firme; recomendación provisional C
- **Decisión:** no se declara A, B ni C como decidida. Recomendación provisional C (MapLibre + PMTiles para el render, núcleo de CoMaps para búsqueda/routing). No se inicia la Fase 1.
- **Motivo:** ruta Madrid–Barcelona 17,8-18,0 s en reposo (umbral 2 s) y búsqueda 631 ms (umbral 100 ms, medida con carga) no cumplen; faltan sin-GMS, gama media, desacople en ejecución. La regla de C exige que el motor pase. Ver `docs/spike-informe.md`.
- **Descartado:** A (umbrales y dependencia de microG); B (sin evidencia de bloqueo de desacople o licencias, y coste del pipeline mundial; sin medir Valhalla); decidir en firme con datos críticos ausentes.
- **Revertir/cerrar:** el usuario elige A/B/C o aporta dispositivos y se repiten las mediciones pendientes (informe, «Qué falta»).

## 2026-10-06 · Se integran las ramas del spike en `master` local con squash
- **Decisión:** `spike/*` y `feat/core-geo-skeleton` se integran en `master` local sin PR (no hay remoto ni CI). 83 tests pasados con `./gradlew test --rerun-tasks` como único «check». Los ficheros `mapas-ci.yml` y `mapas-claude-settings.json` quedaron versionados en la raíz como texto (no activan nada).
- **Revertir:** `git reset --hard 559cf42`... (solo local; las ramas originales siguen existiendo).
