# Releases

Misma mecánica que UltimateDeck: un tag `vX.Y.Z` dispara el workflow **Release**, que compila el APK firmado y lo publica en GitHub Releases con las notas de `CHANGELOG.md`.

## Versiones

- La versión vive en un solo sitio: `appVersion` en `gradle.properties`, en SemVer (`1.2.3`) o `1.2.3-rc.N` para una candidata. Antes de 1.0.0 la app es `0.x`.
- El `versionCode` de Android se deriva, nunca se escribe a mano: `(MAJOR*10000 + MINOR*100 + PATCH) * 100 + N`, con `N = 99` para una versión final. `0.1.0-rc.1` es `10001`; `1.0.0` es `1000099`. Una final siempre ordena después de sus candidatas y nada depende de fechas ni de la máquina (builds reproducibles).

## Firma (una sola vez)

Se firma con la clave propia del proyecto. **Si se pierde, los usuarios tendrían que desinstalar para actualizar:** guárdala con copia de seguridad.

1. Crear la clave (fuera del repo):
   ```sh
   keytool -genkeypair -v -keystore ultimatemaps-release.jks -alias ultimatemaps \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Añadir estos secretos en GitHub (Settings → Secrets and variables → Actions):
   - `UM_KEYSTORE_BASE64`: `base64 -w0 ultimatemaps-release.jks`
   - `UM_KEYSTORE_PASSWORD`, `UM_KEY_ALIAS` (`ultimatemaps`), `UM_KEY_PASSWORD`
3. Para F-Droid, la huella del certificado (`AllowedAPKSigningKeys`):
   ```sh
   keytool -list -v -keystore ultimatemaps-release.jks -alias ultimatemaps | grep SHA256
   ```

Sin esas variables, `./gradlew :app:assembleFossRelease` genera un APK **sin firmar** (`app-foss-release-unsigned.apk`), que es lo que compara F-Droid. El workflow **falla** si falta el secreto, para no publicar nunca un APK sin firmar.

## Hacer un release

1. Mover las notas de `[Unreleased]` en `CHANGELOG.md` bajo `## [X.Y.Z] - AAAA-MM-DD`.
2. Poner `appVersion=X.Y.Z` en `gradle.properties`.
3. Commit (`chore: release X.Y.Z`), merge a `master`, y entonces el tag:
   ```sh
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```
4. El workflow comprueba que el tag coincide con `appVersion` y que hay notas, ejecuta `test`, `lintFossRelease`, compila el APK firmado y publica la Release con `UltimateMaps-X.Y.Z.apk` y su `.sha256`. Las candidatas (`-rc.N`) salen como pre-release.

## Estado de la preparación (2026-10-07)

**Preparado en el repo** (verificado en local, no en GitHub): `LICENSE` (GPL-3.0), `README.md`, `PRIVACY.md` (es/en), `CHANGELOG.md` con notas de `0.1.0-rc.1`, `fastlane/metadata/android/{en-US,es-ES}` (título, descripciones y changelog 10001), borrador `fdroid/com.qtekfun.mapas.yml` (sin la sección `Builds`), versión única `appVersion` con `versionCode` derivado, firma por entorno, `release.yml` (instala NDK y CMake exactos, prepara el submódulo, falla sin clave, publica APK y `.sha256`), `usesCleartextTraffic="false"`, `allowBackup="false"`, y `LICENSES.md` al día (incluido `kdtree++`, Artistic License 2.0 verificada en cabeceras).

### Solo puedes hacerlo tú (CLAUDE.md, «Cuándo preguntar» nº 4 y 5)

1. **Clave y secretos** `UM_KEYSTORE_BASE64`, `UM_KEYSTORE_PASSWORD`, `UM_KEY_ALIAS`, `UM_KEY_PASSWORD` (sección «Firma»). Guarda la clave con copia: si se pierde, hay que desinstalar para actualizar.
2. **Crear `master` en GitHub**: hoy `origin` está vacío y el flujo prohíbe empujar a `master` directamente. Hay que autorizar un primer `git push origin master` (o hacerlo tú) y proteger la rama (plantilla en `~/repos/ruleset-master.json`).
3. **CI (`ci.yml`)**: existe `mapas-ci.yml` en la raíz, con `main` en vez de `master`; tocar `.github/workflows/` requiere tu visto bueno. Sin CI el merge automático no tiene puerta.
4. **Primer tag** `v0.1.0-rc.1` tras poner fecha en `CHANGELOG.md`: `git tag v0.1.0-rc.1 && git push origin v0.1.0-rc.1`.
5. **Avisar al proyecto CoMaps** de que alojamos copias de sus `.mwm` (no encontramos condiciones de uso del CDN) y decidir si se anuncia antes.
6. **Icono y capturas** para las tiendas (`fastlane/.../images/`): hoy hay un `ic_launcher` provisional y ninguna captura.

### Necesita el Pixel 8 (prohibido hasta nuevo aviso del usuario)

- Descarga de una región **desde la app** con la URL por defecto (no probada nunca), con pausa y reanudación.
- Captura de tráfico con el **modo sin red** activo (RF-12: cero conexiones salientes) y al arrancar (debe ser cero).
- Latencia de búsqueda (umbral 100 ms; medida 484-4201 ms) y ruta larga con las 25 regiones (umbral 2 s).
- Fluidez con etiquetas y varias regiones, memoria, y un segundo dispositivo (gama media, sin GMS).
- **Minificado (R8):** hoy desactivado; activarlo exige probar JNI y MapLibre minificados.

### Riesgos conocidos para F-Droid

- **Compilar el núcleo necesita red y PyPI** (`scripts/comaps-prepare.sh` instala `protobuf` con pip y clona ~2 GB de submódulos). Los servidores de F-Droid limitan la red durante la compilación: probablemente haya que **versionar los ficheros generados** (clasificador, categorías, reglas de estilo, cadenas; <3 MB) y dejar el script solo para regenerarlos.
- La entrada `Builds` (versión, `versionCode`, commit, `submodules: true`, `sudo` con JDK 21) se escribe al tener el tag; ver `~/repos/ultimatedeck/fdroid/` como modelo.
- Anti-features: por descargar de GitHub puede aplicarse `NonFreeNet` o similar; lo decide la revisión.
- El texto de la Artistic License 2.0 de `kdtree++` no viene en el submódulo: incluirlo en un `NOTICE` o en «Acerca de» (la app aún no tiene pantalla «Acerca de»; la atribución de OSM sí está siempre en el mapa).
