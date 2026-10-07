# Arrastre del panel inferior (3 detents)

Rama `feat/ui-regions-search-sheet`. Archivos: `app/.../ui/sheet/BottomSheet.kt`, `ui/MapScreen.kt`, `ui/theme/Theme.kt`.

**Aviso honesto: la sensación real NO está medida.** No se usó el Pixel 8. Todo lo de abajo son cambios razonados por lectura del código y comprobados con gestos sintéticos de Robolectric (que verifican la lógica: qué detent, quién hace scroll), no cómo se siente el dedo. Los valores nuevos son una primera estimación y hay que probarlos en el teléfono.

## Qué había (y por qué «cuesta un pelín»)

| Punto | Antes | Problema probable |
|---|---|---|
| Zona de arrastre | Solo el tirador: 5 dp de pastilla + 2×10 dp = 25 dp de alto | Muy por debajo de 48 dp; el resto de la cabecera (pestañas, campo) no arrastraba |
| Slop táctil | `draggable` descarta ~8 dp (`touchSlop`) antes del primer delta | El panel va ~8 dp por detrás del dedo todo el gesto |
| Aplicar el delta | `scope.launch { animatable.snapTo(...) }` en cada delta | Cada evento pasa por una corrutina: posible retardo de un frame |
| Soltar lento | El detent más cercano (hay que pasar la mitad del hueco) | Para subir de MEDIO a COMPLETO hay que arrastrar ~625 px (≈ 240 dp) |
| Fling | 800 px/s fijos (≈ 300 dp/s a 2,6x; distinto según densidad) | Umbral alto y dependiente de la densidad |
| Listas | Sin `nestedScroll` | Arrastrar sobre resultados/listas desplaza la lista; con la lista arriba del todo el gesto «se atasca», el panel no baja |
| Animación | Muelle amortiguamiento 0,85, rigidez 400 (`MediumLow`); al soltar se arrancaba sin velocidad del fling y a veces dos animaciones seguidas (efecto + explícita) | Asentamiento lento (~0,6 s) y con tirón |
| Teclado | Al enfocar un campo el panel pasa a COMPLETO; arrastrar hacia abajo dejaba el teclado y el foco abiertos | El panel y el teclado se «pelean» |
| TalkBack | Solo el clic del tirador (cicla COLLAPSED→MEDIUM→FULL→COLLAPSED) | Sin acciones explícitas de subir/bajar |

## Qué cambia

| Punto | Después |
|---|---|
| Tirador | Caja de **48 dp** de alto a todo el ancho (pastilla centrada). La altura contraída pasa de 84 a **107 dp** para que el contenido visible sea el mismo (+23 dp) |
| Zona de arrastre | **Todo el panel** (`draggable` en la columna): tirador, título, pestañas y cualquier zona que no haga scroll |
| Slop | Se devuelve el `touchSlop` en el primer delta (el panel queda bajo el dedo). Medido en Robolectric: 150 px de dedo = 150 px de panel |
| Delta | Síncrono (`mutableFloatState` leído en la fase de layout, sin recomponer el contenido ni corrutinas por evento) |
| Soltar lento | Si el gesto empezó en un detent, basta **25 %** del hueco hacia el siguiente (antes 50 %); si se pasa de la mitad, el más cercano |
| Fling | **200 dp/s** (antes 800 px/s); fling hacia arriba o abajo va al detent siguiente en esa dirección, sin saltarse ninguno (se mantienen 3 detents) |
| Animación | Muelle amortiguamiento **0,9**, rigidez **800** (antes 0,85 / 400), con la **velocidad del dedo** como velocidad inicial; una sola animación (se ignora la del efecto si ya se va al mismo destino); sin sobrepaso fuera de [colapsado, completo] |
| Listas | `nestedScroll`: deslizar hacia arriba **sube el panel antes** de que la lista se mueva; hacia abajo con la lista arriba del todo **baja el panel**; con la lista desplazada, solo hace scroll la lista. Al soltar entre dos detents, el panel se asienta con la velocidad y la lista no hace fling; si el panel llegó a COMPLETO, la lista sigue con su fling. Flings de la propia lista no mueven el panel (decisión: no colapsar el panel al volver arriba con un fling largo) |
| Teclado | Al asentarse en un detent distinto de COMPLETO por gesto se quita el foco y se oculta el teclado |
| TalkBack | El tirador expone acciones personalizadas «Ampliar el panel» / «Reducir el panel» (solo las posibles, sin dar la vuelta) además del clic existente |

## Pruebas

- `SheetMathTest`: umbral de fling (límites), regla del 25 %, drag que pasa de largo, reparto del scroll anidado, vecinos de detent.
- `SheetGestureTest` (Robolectric): tirador ≥ 48 dp; arrastre iniciado fuera del tirador; fling corto y rápido entre los 3 detents; arrastre lento por debajo/por encima del 25 %; `swipeUp`/`swipeDown` sin lista; con `LazyColumn`: subir antes de hacer scroll, bajar con la lista arriba, lista a pantalla completa, lista desplazada; acciones de semántica.
- Nota de test: `swipe()` del framework interrumpía los arrastres lentos a mitad (la inyección, no el panel), así que los gestos lentos usan `down/moveBy/up`.

## Pendiente de medir en el teléfono

Umbral de 200 dp/s y 25 %, rigidez 800, 107 dp de altura contraída, y si el teclado se oculta en el momento adecuado. Si algo se siente mal, los valores están en `SheetMath` y `SheetMotion.SPRING`.
