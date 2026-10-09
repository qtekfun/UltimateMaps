package com.qtekfun.ultimatemaps.fuel

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.fuel.FuelTypes

/**
 * The localized name of a fuel. `:core-fuel` only knows the Ministry's Spanish product names (kept there as the
 * fallback, and for the data files); the screens show these string resources so the name follows the app language.
 */
object FuelNames {
    @StringRes
    fun resOf(fuelId: String): Int? = when (fuelId) {
        "g95e5" -> R.string.fuel_name_g95e5
        "g95e10" -> R.string.fuel_name_g95e10
        "g95e25" -> R.string.fuel_name_g95e25
        "g95e85" -> R.string.fuel_name_g95e85
        "g95e5p" -> R.string.fuel_name_g95e5p
        "g98e5" -> R.string.fuel_name_g98e5
        "g98e10" -> R.string.fuel_name_g98e10
        "goa" -> R.string.fuel_name_goa
        "goap" -> R.string.fuel_name_goap
        "gob" -> R.string.fuel_name_gob
        "bie" -> R.string.fuel_name_bie
        "bio" -> R.string.fuel_name_bio
        "glp" -> R.string.fuel_name_glp
        "gnc" -> R.string.fuel_name_gnc
        "gnl" -> R.string.fuel_name_gnl
        "h2" -> R.string.fuel_name_h2
        "adblue" -> R.string.fuel_name_adblue
        "dren" -> R.string.fuel_name_dren
        "gren" -> R.string.fuel_name_gren
        "met" -> R.string.fuel_name_met
        "amo" -> R.string.fuel_name_amo
        "bgnc" -> R.string.fuel_name_bgnc
        "bgnl" -> R.string.fuel_name_bgnl
        else -> null
    }

    /** The name in the language of [context]; the Ministry's name when there is no resource, the id as the last resort. */
    fun name(context: Context, fuelId: String): String =
        resOf(fuelId)?.let(context::getString) ?: FuelTypes.byId(fuelId)?.displayName ?: fuelId
}

/** [FuelNames.name] for a composable. */
@Composable
fun fuelName(fuelId: String): String = FuelNames.resOf(fuelId)?.let { stringResource(it) } ?: FuelTypes.byId(fuelId)?.displayName ?: fuelId
