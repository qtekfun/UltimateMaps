# 06 · Playbook de Claude Code (autonomía con guardarraíles)

Objetivo: que Claude Code trabaje sin pedirte aprobación por cada cambio, y se detenga solo en lo que de verdad importa. Datos comprobados en la documentación oficial el 2026-10-06 (code.claude.com/docs: permisos, ajustes y memoria).

## Qué hay que copiar al repositorio

| Archivo del paquete | Destino en el repo | Para qué |
| --- | --- | --- |
| `mapas-CLAUDE.md` | `CLAUDE.md` | Decisiones inamovibles, protocolo de decisiones y cuándo preguntar |
| `mapas-claude-settings.json` | `.claude/settings.json` | Reglas de permisos compartidas del proyecto |
| `mapas-ci.yml` | `.github/workflows/ci.yml` | CI mínimo: la puerta que decide si una PR se puede mergear |

Los ajustes personales (por ejemplo, tus propias aprobaciones) van en `.claude/settings.local.json`, que Claude Code mantiene fuera de git.

## Tres niveles de autonomía

| Nivel | Cómo se activa | Qué hace | Cuándo usarlo |
| --- | --- | --- | --- |
| **1. Edición automática + lista de permitidos** (recomendado de partida) | Ya viene en `mapas-claude-settings.json` (`defaultMode: acceptEdits`) | Acepta ediciones de archivos y `mkdir`, `touch`, `mv`, `cp` en el directorio de trabajo; los comandos de la lista `allow` no piden permiso | Desarrollo normal |
| **2. Modo `auto`** | Lanzar con `claude --permission-mode auto` o fijarlo en `~/.claude/settings.json` | Funciona sin avisos rutinarios; un clasificador en segundo plano revisa que los comandos y las peticiones de red sean coherentes con lo que pediste. Depende de que tu plan o sesión lo tenga disponible | Cuando el nivel 1 siga pidiendo demasiadas confirmaciones |
| **3. `bypassPermissions`** | `claude --permission-mode bypassPermissions` o en tus ajustes de usuario | Se salta los avisos, incluso en rutas protegidas como `.git` y `.claude` | Solo dentro de una máquina virtual o contenedor aislado, sin acceso a nada que importe |

## Cosas importantes de las docs

1. **Aprobar la confianza del proyecto una vez.** Las reglas `allow` del `.claude/settings.json` de un proyecto solo se aplican después de aceptar el diálogo de confianza del directorio. Las reglas `deny` y `ask` se aplican siempre.
2. **`auto` y `bypassPermissions` no funcionan desde los ajustes del proyecto.** Hay que ponerlos en `~/.claude/settings.json` o pasarlos con `--permission-mode`. Por eso el archivo del paquete usa `acceptEdits`.
3. **Orden de evaluación:** primero `deny`, luego `ask`, luego `allow`. Una regla `allow` no puede hacer una excepción a un `deny`.
4. **Sintaxis de reglas Bash:** el `*` va después del subcomando (`Bash(git commit *)`); el espacio antes del `*` forma parte de la regla (`Bash(ls *)` no cubre `lsof`).
5. **Las reglas de Bash no son una frontera de seguridad.** Por ejemplo, `Bash(git push origin main *)` no detiene `git -C . push origin main`. Para garantías reales, usa la protección de rama de GitHub (ver más abajo), el sandbox o un hook `PreToolUse`.
6. **`CLAUDE.md` es contexto, no un mecanismo de bloqueo.** Lo que no debe pasar nunca va en `deny`; lo que debe decidir Claude va en `CLAUDE.md`.
7. **`CLAUDE.md` debería ocupar menos de unas 200 líneas** para que se siga bien. El del paquete cumple esa pauta.

## Qué está permitido, preguntado y bloqueado en el archivo del paquete

- **Permitido sin preguntar:** `./gradlew`, `gradle`, git (`add`, `commit`, `switch`, `checkout -b`, `branch`, `stash`, `merge`, `submodule`, `tag`, `fetch`, `pull` y `push` de ramas), `gh` para PR (`create`, `view`, `list`, `status`, `diff`, `checks`, `comment`, `merge`), ejecuciones de CI (`gh run list/view/watch`) e incidencias (`gh issue create/list/view/comment`), `adb` de desarrollo (instalar, logcat, `dumpsys`, `am start`, `input`, `push`, `pull`), `cmake`, `ninja`, `sdkmanager`, scripts del repo (`./scripts/*`, `python3 scripts/*`), `pmtiles`, `zip`/`unzip`/`tar`, búsqueda web y lectura de documentación de dominios técnicos concretos.
- **Pregunta antes de ejecutar:** `git reset --hard`, `git clean`, `git rebase`, `gh api`, `gh repo`, `gh secret`, `gh release`, `curl`, `wget`, `rm -rf`, y cambios en `LICENSE` y en `.github/workflows/`.
- **Bloqueado siempre:** `sudo`, el push forzado (`--force`, `-f`, `--force-with-lease`, `+rama`), el push directo a `main`, `gh pr merge --admin`, `gh repo delete`, y la lectura de `.env`, `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `~/.ssh` y `~/.gnupg`.

**Si `curl` y `wget` te interrumpen demasiado** (por ejemplo, al descargar mapas), muévelos a `allow`, o mejor, activa el sandbox y permite solo los dominios que necesites (Codeberg, GitHub, el CDN de mapas y los repositorios de Maven/Google).

## Protocolo de decisiones

- Ante una duda, Claude decide con el criterio más razonable y lo registra en `docs/decisions.md` (fecha, decisión, motivo, alternativa descartada).
- Solo se detiene en los cinco casos de «Cuándo preguntar» de `CLAUDE.md`: licencia o dependencia propietaria, cambio de decisiones inamovibles, cambio de A/B/C ya decidido, acciones irreversibles o con coste, y tocar lo que decide si algo se mergea (workflows de CI, protección de rama, permisos del repo).
- Tú revisas el `decisions.md` y las PR ya mergeadas cuando quieras, no en cada paso.

## Push, PR y merge automático

Flujo: rama por tarea → push → `gh pr create` → esperar checks → si todos pasan, merge con squash y borrado de la rama. El detalle está en `CLAUDE.md` (sección «Flujo de ramas y PR»).

**Lo que hace falta preparar una vez en GitHub** (para que «si pasan, que mergee» sea cierto y no solo una intención):

1. **Copiar `mapas-ci.yml`** como `.github/workflows/ci.yml` y empujarlo. Sin CI, no hay checks y el merge no tendría ninguna condición.
2. **Proteger `main`** (Settings → Branches o Rulesets): exigir PR antes de mergear, exigir que el check `build` pase, bloquear los pushes forzados y activar «no permitir saltarse estas reglas» (también para administradores).
3. **Activar «Allow auto-merge» y «Automatically delete head branches»** en Settings → General. Así `gh pr merge --auto --squash` deja la PR programada y GitHub la mergea solo cuando el check termina en verde.
4. **Autenticar `gh`** con `gh auth login`. Lo más seguro es un token de acceso fino limitado a este repositorio, con permisos de lectura y escritura sobre contenido y PR (y sobre workflows si Claude Code debe poder cambiarlos), y sin permisos de administración del repo [comprueba los nombres exactos de los permisos al crearlo].

**Por qué la protección de rama es la garantía real:** las reglas de permisos de Claude Code intentan impedir el push a `main` o el merge con `--admin`, pero son reglas sobre texto de comandos y se pueden esquivar con otras formas. La protección de rama la aplica GitHub en el servidor: aunque Claude Code lo intentase, un push directo o un merge con checks en rojo se rechaza.

**Límite del merge automático:** un check solo detecta lo que los tests y el lint cubren. Por eso el CI debe crecer con el proyecto (tests de parsers de enlaces, importadores, navegación simulada, comprobación de dependencias propietarias, y más adelante, benchmarks de rendimiento). Tras el spike y en cada fase, revisa qué cubre.

**Por qué los workflows piden confirmación:** cambiar `.github/workflows/` equivale a cambiar la condición de merge. El archivo del paquete lo deja en «preguntar». Si prefieres no tener ni esa fricción, muévelo a `allow`, sabiendo que entonces el CI deja de ser un control independiente.

## Bucle de trabajo (spec-driven)

1. Leer el requisito y el criterio de aceptación.
2. Planificar en 3-5 pasos y dividir en tareas pequeñas.
3. Implementar.
4. Compilar, pasar tests y, si toca rendimiento, medir.
5. Un commit por tarea.
6. Actualizar `docs/` y `decisions.md`.

## Prompt inicial sugerido

> Lee `CLAUDE.md` y `docs/mapas-README.md`. Ejecuta el spike de `docs/mapas-04-spike.md` de principio a fin sin pedirme aprobación por cada paso: prepara el entorno, compila CoMaps sin modificar su motor, descarga el mapa de España, mide con los umbrales definidos en los dispositivos que haya conectados, y entrégame `docs/spike-informe.md` con la recomendación A, B o C. Trabaja con ramas y PR como indica `CLAUDE.md`: empuja, abre la PR y mergea cuando los checks pasen. Registra tus decisiones en `docs/decisions.md`. Detente solo en los casos de «Cuándo preguntar».

## Comprobaciones útiles

- `/status`: ver qué archivos de ajustes se han cargado.
- `/permissions`: ver las reglas activas y de qué archivo vienen.
- `/context`: comprobar que `CLAUDE.md` se ha cargado.
- `claude doctor`: ver entradas de ajustes que se han rechazado.

## Antes de dar más autonomía

Si pasas al nivel 3, hazlo en una VM sin tus credenciales, sin acceso a tus servidores y con el repositorio como único contenido de valor.
