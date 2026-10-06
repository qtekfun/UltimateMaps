# Proyecto Mapas (nombre provisional)

App Android de mapas y navegación 100 % offline y privada, con UI estilo Apple Maps, muy fluida. GPLv3, para F-Droid y GitHub. Documentación completa en `docs/` (empieza por `docs/mapas-README.md`).

## Decisiones inamovibles

- Solo Android. Kotlin + Compose para la UI; C++ (NDK/JNI) para el núcleo.
- Mapa, búsqueda y routing en el dispositivo. Sin tráfico. Sin Google, Waze ni Apple como proveedores (solo se interpretan sus enlaces).
- Cero telemetría. Toda la red pasa por `NetworkPolicy`; sin librerías de analítica ni informes de fallos remotos.
- Funciona sin Google Play Services. Prohibido usar `play-services-*`, Firebase o cualquier SDK propietario en el sabor `foss`. Si hay GMS, se aprovecha solo por vías sin dependencias (por ejemplo, `LocationManager.FUSED_PROVIDER`).
- Licencia GPLv3. Antes de añadir una dependencia, comprobar que su licencia es compatible y registrarla en `LICENSES.md`.
- Datos de OpenStreetMap con atribución visible (ODbL).
- Rendimiento: 60/120 fps y arranque en frío ≤ 1 s son requisitos, no deseos. Ver `docs/mapas-02-requisitos.md`.

## Estado actual

Fase 0 (spike). Seguir `docs/mapas-04-spike.md`. La opción de motor (A, B o C) se decide en el informe del spike y se registra en `docs/decisions.md`.

## Comandos

Se completan al terminar el spike. Por defecto:

- Compilar: `./gradlew assembleDebug`
- Tests unitarios: `./gradlew test`
- Tests instrumentados: `./gradlew connectedDebugAndroidTest`
- Lint: `./gradlew lint`

## Cómo trabajar (autonomía)

- Trabaja sin pedir aprobación para editar código, compilar, ejecutar tests, medir, instalar en dispositivos conectados, hacer commits, empujar ramas, abrir PR y mergearlas cuando los checks pasen.
- Divide el trabajo en tareas pequeñas; un commit por tarea con un mensaje claro.
- Antes de dar algo por hecho: compila, pasa los tests y, si afecta a rendimiento, mide.
- Actualiza `docs/` cuando cambie una decisión o un requisito.

## Flujo de ramas y PR (autónomo)

1. Una rama por tarea (`feat/<tarea>`, `fix/<tarea>` o `spike/<tema>`). Nunca trabajes ni empujes directamente a `master`.
2. Commits pequeños y push de la rama con `git push -u origin <rama>`, sin pedir permiso.
3. Abre la PR con `gh pr create`: qué cambia, cómo se probó y qué requisito (RF/RNF) cubre. Una tarea por PR.
4. Espera a los checks con `gh pr checks --watch`. Si todos pasan, mergea con squash y borra la rama: `gh pr merge --squash --delete-branch` (o `--auto` si el repo tiene el auto-merge activado).
5. Si un check falla, arregla en la misma rama y vuelve a empujar. Tras 3 intentos con el mismo fallo, anótalo en `docs/decisions.md`, deja la PR abierta y sigue con otra tarea.
6. Nunca mergees con checks en rojo o pendientes, ni uses `--admin`, ni debilites, borres o desactives tests o checks para que pasen. Si un test es incorrecto, corrígelo y explícalo en la PR.
7. Tras el merge: `git switch master && git pull` y siguiente tarea.

## Protocolo de decisiones

Ante una duda, decide con el criterio más razonable según los documentos, y regístralo en `docs/decisions.md` (fecha, decisión, motivo, alternativa descartada). No te detengas a preguntar por detalles reversibles.

## Cuándo preguntar (y solo entonces)

1. Cambiar de licencia, o añadir una dependencia propietaria o de licencia dudosa.
2. Cambiar una decisión inamovible de este archivo.
3. Cambiar la opción de motor (A, B, C) ya decidida en el informe del spike.
4. Cualquier acción irreversible fuera del repositorio, publicar, enviar datos a terceros o gastar dinero.
5. Tocar lo que decide si algo se mergea: `.github/workflows/`, la protección de rama o los permisos del repo; reescribir historial o borrar ramas protegidas.

## Privacidad y datos del usuario

- Las ubicaciones del usuario nunca se escriben en logs por defecto ni salen del dispositivo.
- Las credenciales (WebDAV) van en Android Keystore.
- No leas ni escribas claves de firma, `.env` ni `keystore.properties`.

## Estilo de código

- Kotlin idiomático, módulos pequeños, interfaces para los motores (`MapEngine`, `SearchEngine`, `RoutingEngine`, `LocationSource`, `VoiceGuide`, `NetworkPolicy`).
- Sin asignaciones en el bucle de render ni en hilos críticos de navegación.
- Cadenas externalizadas (español e inglés) desde el primer día.
- Tests para parsers de enlaces, importadores GPX/KML/Takeout y sync; las pruebas de navegación usan una `LocationSource` simulada.

## Referencias

- Requisitos: `docs/mapas-02-requisitos.md`
- Arquitectura y diseño sin GMS: `docs/mapas-03-arquitectura.md`
- Spike y criterios de decisión: `docs/mapas-04-spike.md`
- Roadmap y riesgos: `docs/mapas-05-roadmap.md`
