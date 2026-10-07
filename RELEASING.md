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

## Pendiente antes del primer release real

- **Núcleo nativo en el APK de release (M2, ya integrado):** el workflow ejecuta `git submodule update --init third_party/comaps` y `scripts/comaps-prepare.sh` (descarga ~2 GB y usa PyPI). En local, `assembleFossRelease` con el núcleo tarda 2 min 51 s (APK sin firmar de 42,3 MB); **no verificado en el runner de GitHub** (NDK 28.2, CMake 3.31.6, tiempo y RAM).
- **Minificado:** desactivado a propósito hasta probar JNI y MapLibre minificados en un dispositivo.
- **F-Droid:** metadatos y `fastlane/` (Fase 5).
- **Secretos, protección de `master` y tag:** los configura el usuario.
