package com.qtekfun.ultimatemaps.places

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.data.SqlitePlacesRepository

/** Opens the on-device database (`databases/places.db`). Blocking: call it off the main thread. */
fun openPlacesService(context: Context): PlacesService {
    val app = context.applicationContext
    val file = app.getDatabasePath("places.db")
    file.parentFile?.mkdirs()
    val repo = SqlitePlacesRepository(AndroidSQLiteDriver(), file.path)
    val prefs = app.getSharedPreferences("places", Context.MODE_PRIVATE)
    val setting = object : LongSetting {
        override fun get(): Long? = prefs.getLong(KEY_DEFAULT_LIST, -1L).takeIf { it >= 0 }
        override fun set(value: Long) = prefs.edit().putLong(KEY_DEFAULT_LIST, value).apply()
    }
    return PlacesService(repo, DefaultList(repo, setting) { app.getString(R.string.list_favorites) })
}

private const val KEY_DEFAULT_LIST = "default_list_id"

/**
 * System document picker (SAF) for importing and exporting GPX/KML: no storage permission is needed and the
 * app never sees paths, only the streams the user chose. Create it in the activity's initialisers.
 */
class DocumentLaunchers(
    private val activity: ComponentActivity,
    private val onImport: (Uri) -> Unit,
    private val onExport: (GeoFormat, Uri) -> Unit,
) {
    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    private val createGpx = activity.registerForActivityResult(ActivityResultContracts.CreateDocument(GeoFormat.GPX.mime)) { uri ->
        if (uri != null) onExport(GeoFormat.GPX, uri)
    }
    private val createKml = activity.registerForActivityResult(ActivityResultContracts.CreateDocument(GeoFormat.KML.mime)) { uri ->
        if (uri != null) onExport(GeoFormat.KML, uri)
    }

    fun pickFile() = open.launch(IMPORT_MIME_TYPES)

    fun createFile(format: GeoFormat, baseName: String) {
        val name = "$baseName.${format.extension}"
        if (format == GeoFormat.KML) createKml.launch(name) else createGpx.launch(name)
    }

    /** Display name of a picked document, for format detection. */
    fun displayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        val IMPORT_MIME_TYPES = arrayOf(
            GeoFormat.GPX.mime, GeoFormat.KML.mime, GeoFormat.KMZ.mime, "application/xml", "text/xml", "application/octet-stream",
            // Google Takeout exports: a ZIP, or one CSV / JSON file of it.
            "application/zip", "application/x-zip-compressed", "text/csv", "text/comma-separated-values", "application/json",
        )
    }
}
