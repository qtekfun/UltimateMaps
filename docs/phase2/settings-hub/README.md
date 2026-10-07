# Settings hub: renders

Renders produced on the JVM with Robolectric native graphics and `captureToImage`
(`app/src/test/kotlin/com/qtekfun/ultimatemaps/settings/SettingsRenderTest.kt`, skipped unless `RENDER_SETTINGS_HUB` names the
output directory). Phone width 411 dp at xxhdpi, light and dark. The status bar is not simulated.

**Not measured:** real look and feel on a device (fonts, touch comfort, TalkBack, animation). Nothing here comes from a phone.

Regenerate: `RENDER_SETTINGS_HUB=$PWD/docs/phase2/settings-hub ./gradlew :app:testFossDebugUnitTest --tests '*SettingsRenderTest*'`

| File | State |
| --- | --- |
| `after-hub` | The hub: search field, six category rows with icon, title and summary. |
| `after-category-navigation` | Navigation and voice, grouped under Voice / Units and map view / Route defaults. |
| `after-category-fuel-advanced-open` | Petrol stations with the Advanced group opened (refresh, source address). |
| `after-category-data` | Data: recent searches, recording, backup; delete actions set apart. |
| `after-map-buttons-north-up` | Map buttons: my location, Settings. Attribution and scale bar top-left. |
| `after-map-buttons-rotated` | Map rotated: my location, compass, Settings. |
| `after-map-buttons-rotated-glove` | Same in glove mode (56 dp buttons). |
