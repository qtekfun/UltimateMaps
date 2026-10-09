package com.qtekfun.ultimatemaps.cameras

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.cameras.CameraSources

/** The data credits of the speed-camera and traffic layers, as string resources so they follow the app language. */
object CameraCredits {
    /** Credit for a camera file whose [sourceFlags] say which sources it holds. */
    fun cameras(context: Context, sourceFlags: Int): String = buildList {
        if (sourceFlags and CameraSources.DGT != 0) add(context.getString(R.string.attr_dgt))
        if (sourceFlags and CameraSources.OSM != 0) add(context.getString(R.string.attr_cameras_osm))
    }.joinToString(" ")

    /** Credit for the live incident feed. */
    fun incidents(context: Context): String = context.getString(R.string.attr_dgt)
}

@Composable
fun camerasCredit(sourceFlags: Int): String = CameraCredits.cameras(LocalContext.current, sourceFlags)

@Composable
fun incidentsCredit(): String = stringResource(R.string.attr_dgt)
