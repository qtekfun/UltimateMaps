package com.qtekfun.ultimatemaps.core.voice

/**
 * The sentences of the spoken guidance in one language, as templates. Spanish and English are written as code
 * (`InstructionText`, `TransitPhrases`, `AlertPhrases`, `ZbePhrases`); every other language is one [VoicePack] in
 * [VoicePacks], so adding a language is adding data, with `VoicePacksTest` checking that nothing is missing.
 *
 * A template may hold these slots, written in braces: `{n}` a number, `{u}` a distance unit, `{s}` a street, `{ref}` an exit
 * number, `{x}` another phrase, `{side}` "on the left", `{lead}` the distance lead ("In 300 meters"), `{line}`, `{to}`, `{stop}`
 * parts of a transit prompt. `fill` replaces them; a slot a template does not use is simply not filled.
 */
class VoicePack(
    val instructions: InstructionPhrases,
    val transit: TransitPromptPhrases,
    val alerts: AlertPromptPhrases,
    /** Low-emission zone ahead; `{lead}` is the distance lead. */
    val zbeAhead: String,
)

/** A phrase that reads differently with a street name: [plain] without one, [withStreet] (holding `{s}`) with it. */
class StreetPhrase(val plain: String, val withStreet: String) {
    fun of(street: String?): String = if (street == null) plain else withStreet.fill("s" to street)
}

/** Replaces the `{slots}` of a template. */
fun String.fill(vararg slots: Pair<String, String>): String {
    var out = this
    for ((name, value) in slots) out = out.replace("{$name}", value)
    return out
}

/** Maneuver sentences. Every sentence starts in lower case: the first letter is capitalized when it is spoken. */
class InstructionPhrases(
    /** "now": the lead of a prompt for something that happens right away. */
    val now: String,
    /** What "Test voice" says before the sample prompt. */
    val testIntro: String,
    /** Joins two signposted destinations ("A 2 and Alcalá de Henares"). */
    val and: String,
    /** "In {n} {u}". */
    val distanceLead: String,
    val meters: String,
    val kilometerOne: String,
    val kilometers: String,
    val feet: String,
    val mileOne: String,
    val miles: String,
    /** True when the singular unit is used below 2 ("1,5 kilomètre"), as in French. */
    val singularBelowTwo: Boolean = false,
    val depart: StreetPhrase,
    val straight: StreetPhrase,
    val slightRight: StreetPhrase,
    val right: StreetPhrase,
    val sharpRight: StreetPhrase,
    val slightLeft: StreetPhrase,
    val left: StreetPhrase,
    val sharpLeft: StreetPhrase,
    val uTurnLeft: String,
    val uTurnRight: String,
    val roundaboutEnter: String,
    /** "At the roundabout, take {x}" with `{x}` one of [exitOrdinals] (or [exitNumber]). */
    val roundaboutTake: String,
    /** The first to the tenth exit as they follow "take" ("the first exit"). */
    val exitOrdinals: List<String>,
    /** The exit past the tenth: "exit number {n}". */
    val exitNumber: String,
    val roundaboutLeave: StreetPhrase,
    /** Added after a sentence to say where it leads: " toward {s}". */
    val toward: String,
    val merge: StreetPhrase,
    val exitLeft: String,
    val exitRight: String,
    val exitStraight: String,
    /** The same with the exit number, `{ref}`. */
    val exitRefLeft: String,
    val exitRefRight: String,
    val exitRefStraight: String,
    val arriveNow: String,
    /** `{side}` is [sideLeft] or [sideRight]. */
    val arriveNowSide: String,
    /** `{lead}` is the distance lead. */
    val arriveSoon: String,
    val arriveSoonSide: String,
    val sideLeft: String,
    val sideRight: String,
    val laneLeftOne: String,
    /** `{n}` lanes. */
    val laneLeftMany: String,
    val laneRightOne: String,
    val laneRightMany: String,
    val laneCenter: String,
    /** "Keep in the {ord} lane from the left", `{ord}` one of [laneOrdinals]. */
    val laneFromLeft: String,
    /** The second to the fifth lane. */
    val laneOrdinals: List<String>,
    val recalculating: String,
    val offRoute: String,
    val arrived: String,
    val stopReached: String,
)

/**
 * Prompts of the public-transport trip follower. A "line" is [lineNamed] (`{line}`) or [lineNext] when the plan names none;
 * [lineTowards] adds `{to}` the head sign. The templates that take `{x}`/`{line}` receive that phrase whole.
 */
class TransitPromptPhrases(
    val lineNext: String,
    val lineNamed: String,
    val lineTowards: String,
    val boardNow: String,
    /** `{stop}`: the position is only a timetable estimate. */
    val getReadyEstimated: String,
    val getReadyStop: String,
    val getReadyNone: String,
    val getOffEstimated: String,
    val getOffStop: String,
    val getOffNone: String,
    val changeHere: String,
    val connectionAtRisk: String,
    val connectionMissed: String,
    val offPlan: String,
    val arrived: String,
)

/** What an alert is about; the sentence is "{lead}, {what}" plus the limit and the "slow down" tails. */
class AlertPromptPhrases(
    val fixedCamera: String,
    val section: String,
    val mobileZone: String,
    val v16: String,
    val accident: String,
    val closure: String,
    val congestion: String,
    val obstacle: String,
    /** ". Limit {n}". */
    val limit: String,
    /** ". Slow down". */
    val slowDown: String,
)
