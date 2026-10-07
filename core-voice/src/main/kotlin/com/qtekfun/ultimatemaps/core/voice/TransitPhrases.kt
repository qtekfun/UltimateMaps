package com.qtekfun.ultimatemaps.core.voice

import com.qtekfun.ultimatemaps.core.transit.follow.FollowPrompt
import com.qtekfun.ultimatemaps.core.transit.follow.PromptKind

/**
 * The sentences of the public-transport trip follower, Spanish and English (pure; the cases are in `TransitPhrasesTest`).
 * When the position behind a prompt is only a timetable estimate (no GPS underground) the wording says so ("you should be
 * ..."), never claiming a certainty the follower does not have.
 */
object TransitPhrases {
    fun of(p: FollowPrompt, language: VoiceLanguage): String {
        val es = language == VoiceLanguage.ES
        val line = p.line?.takeIf { it.isNotBlank() }
        val towards = p.headsign?.takeIf { it.isNotBlank() }
        val stop = p.stop?.takeIf { it.isNotBlank() }
        val lineText = when {
            line == null -> if (es) "el siguiente transporte" else "the next service"
            es -> "la línea $line"
            else -> "line $line"
        }
        val lineTowards = if (towards == null) lineText else if (es) "$lineText hacia $towards" else "$lineText towards $towards"
        return when (p.kind) {
            PromptKind.BOARD_NOW -> if (es) "Sube ahora a $lineTowards" else "Board $lineTowards now"
            PromptKind.GET_READY -> when {
                p.estimated && stop != null -> if (es) "Según el horario, estás llegando a $stop. Prepárate para bajar" else "By the timetable you are nearly at $stop. Get ready to get off"
                stop != null -> if (es) "Prepárate para bajar en $stop, la próxima parada" else "Get ready to get off at $stop, the next stop"
                else -> if (es) "Prepárate para bajar en la próxima parada" else "Get ready to get off at the next stop"
            }
            PromptKind.GET_OFF_NOW -> when {
                p.estimated && stop != null -> if (es) "Según el horario, es la hora de bajar en $stop" else "By the timetable it is time to get off at $stop"
                stop != null -> if (es) "Baja ahora en $stop" else "Get off now at $stop"
                else -> if (es) "Baja ahora" else "Get off now"
            }
            PromptKind.CHANGE_HERE -> if (es) "Cambia aquí a $lineTowards" else "Change here to $lineTowards"
            PromptKind.CONNECTION_AT_RISK -> if (es) "Tu conexión con $lineText está en riesgo" else "Your connection to $lineText is at risk"
            PromptKind.CONNECTION_MISSED -> if (es) "Puede que hayas perdido $lineText. Puedes calcular otra ruta" else "You may have missed $lineText. You can plan again"
            PromptKind.OFF_PLAN -> if (es) "Estás fuera del itinerario. Puedes calcular otra ruta" else "You are off the planned trip. You can plan again"
            PromptKind.ARRIVED -> if (es) "Has llegado" else "You have arrived"
        }
    }

    /** Boarding, getting off and arriving cannot wait; the rest is spoken in turn. */
    fun priority(kind: PromptKind): VoicePriority = when (kind) {
        PromptKind.BOARD_NOW, PromptKind.GET_OFF_NOW, PromptKind.ARRIVED -> VoicePriority.URGENT
        else -> VoicePriority.NORMAL
    }

    /** The queue key: a newer prompt of the same kind replaces a pending one. */
    fun key(kind: PromptKind): String = "transit:" + kind.name
}
