package com.qtekfun.ultimatemaps.core.voice

/**
 * The spoken-guidance packs of the languages other than Spanish and English: Catalan, Galician, French, German, Portuguese
 * (European) and Italian. Basque has no pack yet (its grammar puts the street name before a case ending, which the
 * templates cannot express); a Basque phone gets English guidance until someone writes one.
 *
 * The wording has been written from knowledge of the languages and has not been reviewed by native speakers; the
 * sentences are short and use only common words so a reviewer can fix a phrase by editing one line.
 */
object VoicePacks {
    /** The pack of [language], or null for Spanish and English (written as code) and for a language without one. */
    fun of(language: VoiceLanguage): VoicePack? = when (language) {
        VoiceLanguage.CA -> CATALAN
        VoiceLanguage.GL -> GALICIAN
        VoiceLanguage.FR -> FRENCH
        VoiceLanguage.DE -> GERMAN
        VoiceLanguage.PT -> PORTUGUESE
        VoiceLanguage.IT -> ITALIAN
        VoiceLanguage.ES, VoiceLanguage.EN -> null
    }

    private fun sp(plain: String, withStreet: String) = StreetPhrase(plain, withStreet)

    // ------------------------------------------------------------------------------------------------ Catalan

    val CATALAN = VoicePack(
        instructions = InstructionPhrases(
            now = "ara", testIntro = "Guia per veu activada", and = "i",
            distanceLead = "D'aquí a {n} {u}", meters = "metres", kilometerOne = "quilòmetre", kilometers = "quilòmetres",
            feet = "peus", mileOne = "milla", miles = "milles",
            depart = sp("inicia la ruta", "inicia la ruta per {s}"),
            straight = sp("continua recte", "continua recte per {s}"),
            slightRight = sp("gira lleugerament a la dreta", "gira lleugerament a la dreta per {s}"),
            right = sp("gira a la dreta", "gira a la dreta per {s}"),
            sharpRight = sp("gira tancat a la dreta", "gira tancat a la dreta per {s}"),
            slightLeft = sp("gira lleugerament a l'esquerra", "gira lleugerament a l'esquerra per {s}"),
            left = sp("gira a l'esquerra", "gira a l'esquerra per {s}"),
            sharpLeft = sp("gira tancat a l'esquerra", "gira tancat a l'esquerra per {s}"),
            uTurnLeft = "fes un canvi de sentit a l'esquerra", uTurnRight = "fes un canvi de sentit a la dreta",
            roundaboutEnter = "entra a la rotonda", roundaboutTake = "a la rotonda, pren {x}",
            exitOrdinals = listOf(
                "la primera sortida", "la segona sortida", "la tercera sortida", "la quarta sortida", "la cinquena sortida",
                "la sisena sortida", "la setena sortida", "la vuitena sortida", "la novena sortida", "la desena sortida",
            ),
            exitNumber = "la sortida número {n}",
            roundaboutLeave = sp("surt de la rotonda", "surt de la rotonda cap a {s}"), toward = " cap a {s}",
            merge = sp("incorpora't a la via", "incorpora't a {s}"),
            exitLeft = "pren la sortida de l'esquerra", exitRight = "pren la sortida de la dreta", exitStraight = "pren la sortida",
            exitRefLeft = "pren la sortida {ref} a l'esquerra", exitRefRight = "pren la sortida {ref} a la dreta",
            exitRefStraight = "pren la sortida {ref}",
            arriveNow = "Has arribat a la destinació", arriveNowSide = "Has arribat a la destinació, {side}",
            arriveSoon = "{lead}, arribaràs a la destinació", arriveSoonSide = "{lead}, la destinació serà {side}",
            sideLeft = "a l'esquerra", sideRight = "a la dreta",
            laneLeftOne = "Mantén-te al carril de l'esquerra", laneLeftMany = "Mantén-te als {n} carrils de l'esquerra",
            laneRightOne = "Mantén-te al carril de la dreta", laneRightMany = "Mantén-te als {n} carrils de la dreta",
            laneCenter = "Mantén-te al carril central", laneFromLeft = "Mantén-te al {ord} carril per l'esquerra",
            laneOrdinals = listOf("segon", "tercer", "quart", "cinquè"),
            recalculating = "Recalculant", offRoute = "Has sortit de la ruta",
            arrived = "Has arribat a la destinació", stopReached = "Parada assolida",
        ),
        transit = TransitPromptPhrases(
            lineNext = "el proper servei", lineNamed = "la línia {line}", lineTowards = "{line} direcció {to}",
            boardNow = "Agafa ara {x}",
            getReadyEstimated = "Segons l'horari, estàs arribant a {stop}. Prepara't per baixar",
            getReadyStop = "Prepara't per baixar a {stop}, la propera parada",
            getReadyNone = "Prepara't per baixar a la propera parada",
            getOffEstimated = "Segons l'horari, és hora de baixar a {stop}",
            getOffStop = "Baixa ara a {stop}", getOffNone = "Baixa ara",
            changeHere = "Canvia aquí i agafa {x}",
            connectionAtRisk = "La teva connexió amb {line} està en risc",
            connectionMissed = "Pot ser que hagis perdut {line}. Pots tornar a planificar",
            offPlan = "Ets fora de l'itinerari previst. Pots tornar a planificar", arrived = "Has arribat",
        ),
        alerts = AlertPromptPhrases(
            fixedCamera = "possible radar fix", section = "tram amb control de velocitat mitjana",
            mobileZone = "zona amb possibles radars mòbils", v16 = "vehicle aturat amb balisa V16", accident = "accident",
            closure = "carretera tallada", congestion = "trànsit lent", obstacle = "obstacle a la via",
            limit = ". Límit {n}", slowDown = ". Redueix la velocitat",
        ),
        zbeAhead = "{lead}, zona de baixes emissions. Consulta les normes d'accés",
    )

    // ---------------------------------------------------------------------------------------------- Galician

    val GALICIAN = VoicePack(
        instructions = InstructionPhrases(
            now = "agora", testIntro = "Guía por voz activada", and = "e",
            distanceLead = "En {n} {u}", meters = "metros", kilometerOne = "quilómetro", kilometers = "quilómetros",
            feet = "pés", mileOne = "milla", miles = "millas",
            depart = sp("inicia a ruta", "inicia a ruta por {s}"),
            straight = sp("segue recto", "segue recto por {s}"),
            slightRight = sp("xira lixeiramente á dereita", "xira lixeiramente á dereita en {s}"),
            right = sp("xira á dereita", "xira á dereita en {s}"),
            sharpRight = sp("xira pechado á dereita", "xira pechado á dereita en {s}"),
            slightLeft = sp("xira lixeiramente á esquerda", "xira lixeiramente á esquerda en {s}"),
            left = sp("xira á esquerda", "xira á esquerda en {s}"),
            sharpLeft = sp("xira pechado á esquerda", "xira pechado á esquerda en {s}"),
            uTurnLeft = "fai un cambio de sentido á esquerda", uTurnRight = "fai un cambio de sentido á dereita",
            roundaboutEnter = "entra na rotonda", roundaboutTake = "na rotonda, colle {x}",
            exitOrdinals = listOf(
                "a primeira saída", "a segunda saída", "a terceira saída", "a cuarta saída", "a quinta saída",
                "a sexta saída", "a sétima saída", "a oitava saída", "a novena saída", "a décima saída",
            ),
            exitNumber = "a saída número {n}",
            roundaboutLeave = sp("sae da rotonda", "sae da rotonda cara a {s}"), toward = " cara a {s}",
            merge = sp("incorpórate á vía", "incorpórate a {s}"),
            exitLeft = "colle a saída da esquerda", exitRight = "colle a saída da dereita", exitStraight = "colle a saída",
            exitRefLeft = "colle a saída {ref} á esquerda", exitRefRight = "colle a saída {ref} á dereita",
            exitRefStraight = "colle a saída {ref}",
            arriveNow = "Chegaches ao teu destino", arriveNowSide = "Chegaches ao teu destino, {side}",
            arriveSoon = "{lead}, chegarás ao teu destino", arriveSoonSide = "{lead}, o teu destino estará {side}",
            sideLeft = "á esquerda", sideRight = "á dereita",
            laneLeftOne = "Mantente no carril da esquerda", laneLeftMany = "Mantente nos {n} carrís da esquerda",
            laneRightOne = "Mantente no carril da dereita", laneRightMany = "Mantente nos {n} carrís da dereita",
            laneCenter = "Mantente no carril central", laneFromLeft = "Mantente no {ord} carril pola esquerda",
            laneOrdinals = listOf("segundo", "terceiro", "cuarto", "quinto"),
            recalculating = "Recalculando", offRoute = "Saíches da ruta",
            arrived = "Chegaches ao teu destino", stopReached = "Parada alcanzada",
        ),
        transit = TransitPromptPhrases(
            lineNext = "o seguinte servizo", lineNamed = "a liña {line}", lineTowards = "{line} dirección {to}",
            boardNow = "Colle agora {x}",
            getReadyEstimated = "Segundo o horario, estás chegando a {stop}. Prepárate para baixar",
            getReadyStop = "Prepárate para baixar en {stop}, a próxima parada",
            getReadyNone = "Prepárate para baixar na próxima parada",
            getOffEstimated = "Segundo o horario, é hora de baixar en {stop}",
            getOffStop = "Baixa agora en {stop}", getOffNone = "Baixa agora",
            changeHere = "Cambia aquí e colle {x}",
            connectionAtRisk = "A túa conexión con {line} está en risco",
            connectionMissed = "Pode que perdeses {line}. Podes volver planificar",
            offPlan = "Estás fóra do itinerario previsto. Podes volver planificar", arrived = "Chegaches",
        ),
        alerts = AlertPromptPhrases(
            fixedCamera = "posible radar fixo", section = "treito con control de velocidade media",
            mobileZone = "zona con posibles radares móbiles", v16 = "vehículo detido con baliza V16", accident = "accidente",
            closure = "estrada cortada", congestion = "tráfico lento", obstacle = "obstáculo na vía",
            limit = ". Límite {n}", slowDown = ". Reduce a velocidade",
        ),
        zbeAhead = "{lead}, zona de baixas emisións. Consulta as normas de acceso",
    )

    // ------------------------------------------------------------------------------------------------ French

    val FRENCH = VoicePack(
        instructions = InstructionPhrases(
            now = "maintenant", testIntro = "Guidage vocal activé", and = "et",
            distanceLead = "Dans {n} {u}", meters = "mètres", kilometerOne = "kilomètre", kilometers = "kilomètres",
            feet = "pieds", mileOne = "mile", miles = "miles", singularBelowTwo = true,
            depart = sp("démarrez l'itinéraire", "démarrez l'itinéraire sur {s}"),
            straight = sp("continuez tout droit", "continuez tout droit sur {s}"),
            slightRight = sp("serrez à droite", "serrez à droite sur {s}"),
            right = sp("tournez à droite", "tournez à droite sur {s}"),
            sharpRight = sp("tournez franchement à droite", "tournez franchement à droite sur {s}"),
            slightLeft = sp("serrez à gauche", "serrez à gauche sur {s}"),
            left = sp("tournez à gauche", "tournez à gauche sur {s}"),
            sharpLeft = sp("tournez franchement à gauche", "tournez franchement à gauche sur {s}"),
            uTurnLeft = "faites demi-tour à gauche", uTurnRight = "faites demi-tour à droite",
            roundaboutEnter = "entrez dans le rond-point", roundaboutTake = "au rond-point, prenez {x}",
            exitOrdinals = listOf(
                "la première sortie", "la deuxième sortie", "la troisième sortie", "la quatrième sortie", "la cinquième sortie",
                "la sixième sortie", "la septième sortie", "la huitième sortie", "la neuvième sortie", "la dixième sortie",
            ),
            exitNumber = "la sortie numéro {n}",
            roundaboutLeave = sp("quittez le rond-point", "quittez le rond-point en direction de {s}"), toward = " en direction de {s}",
            merge = sp("insérez-vous", "insérez-vous sur {s}"),
            exitLeft = "prenez la sortie à gauche", exitRight = "prenez la sortie à droite", exitStraight = "prenez la sortie",
            exitRefLeft = "prenez la sortie {ref} à gauche", exitRefRight = "prenez la sortie {ref} à droite",
            exitRefStraight = "prenez la sortie {ref}",
            arriveNow = "Vous êtes arrivé à destination", arriveNowSide = "Vous êtes arrivé à destination, {side}",
            arriveSoon = "{lead}, vous arriverez à destination", arriveSoonSide = "{lead}, votre destination sera {side}",
            sideLeft = "à gauche", sideRight = "à droite",
            laneLeftOne = "Restez sur la voie de gauche", laneLeftMany = "Restez sur les {n} voies de gauche",
            laneRightOne = "Restez sur la voie de droite", laneRightMany = "Restez sur les {n} voies de droite",
            laneCenter = "Restez sur la voie centrale", laneFromLeft = "Restez sur la {ord} voie en partant de la gauche",
            laneOrdinals = listOf("deuxième", "troisième", "quatrième", "cinquième"),
            recalculating = "Recalcul de l'itinéraire", offRoute = "Vous avez quitté l'itinéraire",
            arrived = "Vous êtes arrivé à destination", stopReached = "Étape atteinte",
        ),
        transit = TransitPromptPhrases(
            lineNext = "le prochain service", lineNamed = "la ligne {line}", lineTowards = "{line} en direction de {to}",
            boardNow = "Montez maintenant dans {x}",
            getReadyEstimated = "D'après l'horaire, vous arrivez à {stop}. Préparez-vous à descendre",
            getReadyStop = "Préparez-vous à descendre à {stop}, le prochain arrêt",
            getReadyNone = "Préparez-vous à descendre au prochain arrêt",
            getOffEstimated = "D'après l'horaire, il est temps de descendre à {stop}",
            getOffStop = "Descendez maintenant à {stop}", getOffNone = "Descendez maintenant",
            changeHere = "Changez ici pour {x}",
            connectionAtRisk = "Votre correspondance avec {line} est en danger",
            connectionMissed = "Vous avez peut-être manqué {line}. Vous pouvez planifier de nouveau",
            offPlan = "Vous êtes hors de l'itinéraire prévu. Vous pouvez planifier de nouveau", arrived = "Vous êtes arrivé",
        ),
        alerts = AlertPromptPhrases(
            fixedCamera = "radar fixe possible", section = "tronçon à vitesse moyenne contrôlée",
            mobileZone = "zone avec radars mobiles possibles", v16 = "véhicule arrêté avec balise V16", accident = "accident",
            closure = "route fermée", congestion = "circulation ralentie", obstacle = "obstacle sur la route",
            limit = ". Limite {n}", slowDown = ". Ralentissez",
        ),
        zbeAhead = "{lead}, zone à faibles émissions. Consultez les règles d'accès",
    )

    // ------------------------------------------------------------------------------------------------ German

    val GERMAN = VoicePack(
        instructions = InstructionPhrases(
            now = "jetzt", testIntro = "Sprachführung ist aktiv", and = "und",
            distanceLead = "In {n} {u}", meters = "Metern", kilometerOne = "Kilometer", kilometers = "Kilometern",
            feet = "Fuß", mileOne = "Meile", miles = "Meilen",
            depart = sp("Route starten", "Route starten auf {s}"),
            straight = sp("geradeaus weiterfahren", "geradeaus weiterfahren auf {s}"),
            slightRight = sp("halb rechts halten", "halb rechts halten auf {s}"),
            right = sp("rechts abbiegen", "rechts abbiegen auf {s}"),
            sharpRight = sp("scharf rechts abbiegen", "scharf rechts abbiegen auf {s}"),
            slightLeft = sp("halb links halten", "halb links halten auf {s}"),
            left = sp("links abbiegen", "links abbiegen auf {s}"),
            sharpLeft = sp("scharf links abbiegen", "scharf links abbiegen auf {s}"),
            uTurnLeft = "nach links wenden", uTurnRight = "nach rechts wenden",
            roundaboutEnter = "in den Kreisverkehr einfahren", roundaboutTake = "im Kreisverkehr {x} nehmen",
            exitOrdinals = listOf(
                "die erste Ausfahrt", "die zweite Ausfahrt", "die dritte Ausfahrt", "die vierte Ausfahrt", "die fünfte Ausfahrt",
                "die sechste Ausfahrt", "die siebte Ausfahrt", "die achte Ausfahrt", "die neunte Ausfahrt", "die zehnte Ausfahrt",
            ),
            exitNumber = "die Ausfahrt Nummer {n}",
            roundaboutLeave = sp("den Kreisverkehr verlassen", "den Kreisverkehr verlassen Richtung {s}"), toward = " Richtung {s}",
            merge = sp("einfädeln", "einfädeln auf {s}"),
            exitLeft = "die Ausfahrt links nehmen", exitRight = "die Ausfahrt rechts nehmen", exitStraight = "die Ausfahrt nehmen",
            exitRefLeft = "Ausfahrt {ref} links nehmen", exitRefRight = "Ausfahrt {ref} rechts nehmen",
            exitRefStraight = "Ausfahrt {ref} nehmen",
            arriveNow = "Sie haben Ihr Ziel erreicht", arriveNowSide = "Sie haben Ihr Ziel erreicht, es liegt {side}",
            arriveSoon = "{lead}, haben Sie Ihr Ziel erreicht", arriveSoonSide = "{lead}, liegt Ihr Ziel {side}",
            sideLeft = "links", sideRight = "rechts",
            laneLeftOne = "Halten Sie sich auf der linken Spur", laneLeftMany = "Halten Sie sich auf den linken {n} Spuren",
            laneRightOne = "Halten Sie sich auf der rechten Spur", laneRightMany = "Halten Sie sich auf den rechten {n} Spuren",
            laneCenter = "Halten Sie sich auf der mittleren Spur", laneFromLeft = "Halten Sie sich auf der {ord} Spur von links",
            laneOrdinals = listOf("zweiten", "dritten", "vierten", "fünften"),
            recalculating = "Route wird neu berechnet", offRoute = "Sie haben die Route verlassen",
            arrived = "Sie haben Ihr Ziel erreicht", stopReached = "Zwischenstopp erreicht",
        ),
        transit = TransitPromptPhrases(
            lineNext = "die nächste Verbindung", lineNamed = "Linie {line}", lineTowards = "{line} Richtung {to}",
            boardNow = "Jetzt einsteigen: {x}",
            getReadyEstimated = "Laut Fahrplan erreichen Sie gleich {stop}. Bereiten Sie sich auf das Aussteigen vor",
            getReadyStop = "Bereiten Sie sich auf das Aussteigen an der nächsten Haltestelle vor: {stop}",
            getReadyNone = "Bereiten Sie sich auf das Aussteigen an der nächsten Haltestelle vor",
            getOffEstimated = "Laut Fahrplan ist es Zeit, an der Haltestelle {stop} auszusteigen",
            getOffStop = "Jetzt an der Haltestelle {stop} aussteigen", getOffNone = "Jetzt aussteigen",
            changeHere = "Hier umsteigen: {x}",
            connectionAtRisk = "Ihr Anschluss an {line} ist gefährdet",
            connectionMissed = "Sie haben {line} möglicherweise verpasst. Sie können neu planen",
            offPlan = "Sie sind von der geplanten Fahrt abgewichen. Sie können neu planen", arrived = "Sie sind angekommen",
        ),
        alerts = AlertPromptPhrases(
            fixedCamera = "mögliche feste Radarkamera", section = "Abschnittskontrolle",
            mobileZone = "Bereich mit möglichen mobilen Radarkontrollen", v16 = "haltendes Fahrzeug mit V16-Warnleuchte",
            accident = "Unfall", closure = "Straßensperrung", congestion = "zähflüssiger Verkehr", obstacle = "Hindernis auf der Fahrbahn",
            limit = ". Tempolimit {n}", slowDown = ". Bitte langsamer fahren",
        ),
        zbeAhead = "{lead}, Umweltzone. Prüfen Sie die Zufahrtsregeln",
    )

    // ------------------------------------------------------------------------------- Portuguese (European)

    val PORTUGUESE = VoicePack(
        instructions = InstructionPhrases(
            now = "agora", testIntro = "Voz de navegação ativada", and = "e",
            distanceLead = "Em {n} {u}", meters = "metros", kilometerOne = "quilómetro", kilometers = "quilómetros",
            feet = "pés", mileOne = "milha", miles = "milhas",
            depart = sp("inicie o percurso", "inicie o percurso em {s}"),
            straight = sp("siga em frente", "siga em frente por {s}"),
            slightRight = sp("mantenha-se ligeiramente à direita", "mantenha-se ligeiramente à direita para {s}"),
            right = sp("vire à direita", "vire à direita para {s}"),
            sharpRight = sp("vire acentuadamente à direita", "vire acentuadamente à direita para {s}"),
            slightLeft = sp("mantenha-se ligeiramente à esquerda", "mantenha-se ligeiramente à esquerda para {s}"),
            left = sp("vire à esquerda", "vire à esquerda para {s}"),
            sharpLeft = sp("vire acentuadamente à esquerda", "vire acentuadamente à esquerda para {s}"),
            uTurnLeft = "faça inversão de marcha à esquerda", uTurnRight = "faça inversão de marcha à direita",
            roundaboutEnter = "entre na rotunda", roundaboutTake = "na rotunda, saia pela {x}",
            exitOrdinals = listOf(
                "primeira saída", "segunda saída", "terceira saída", "quarta saída", "quinta saída",
                "sexta saída", "sétima saída", "oitava saída", "nona saída", "décima saída",
            ),
            exitNumber = "saída número {n}",
            roundaboutLeave = sp("saia da rotunda", "saia da rotunda em direção a {s}"), toward = " em direção a {s}",
            merge = sp("integre-se na via", "integre-se em {s}"),
            exitLeft = "apanhe a saída à esquerda", exitRight = "apanhe a saída à direita", exitStraight = "apanhe a saída",
            exitRefLeft = "apanhe a saída {ref} à esquerda", exitRefRight = "apanhe a saída {ref} à direita",
            exitRefStraight = "apanhe a saída {ref}",
            arriveNow = "Chegou ao seu destino", arriveNowSide = "Chegou ao seu destino, {side}",
            arriveSoon = "{lead}, chegará ao seu destino", arriveSoonSide = "{lead}, o seu destino ficará {side}",
            sideLeft = "à esquerda", sideRight = "à direita",
            laneLeftOne = "Mantenha-se na faixa da esquerda", laneLeftMany = "Mantenha-se nas {n} faixas da esquerda",
            laneRightOne = "Mantenha-se na faixa da direita", laneRightMany = "Mantenha-se nas {n} faixas da direita",
            laneCenter = "Mantenha-se na faixa central", laneFromLeft = "Mantenha-se na {ord} faixa a contar da esquerda",
            laneOrdinals = listOf("segunda", "terceira", "quarta", "quinta"),
            recalculating = "A recalcular", offRoute = "Saiu da rota",
            arrived = "Chegou ao seu destino", stopReached = "Paragem alcançada",
        ),
        transit = TransitPromptPhrases(
            lineNext = "o próximo serviço", lineNamed = "a linha {line}", lineTowards = "{line} em direção a {to}",
            boardNow = "Apanhe agora {x}",
            getReadyEstimated = "Segundo o horário, está a chegar a {stop}. Prepare-se para sair",
            getReadyStop = "Prepare-se para sair em {stop}, a próxima paragem",
            getReadyNone = "Prepare-se para sair na próxima paragem",
            getOffEstimated = "Segundo o horário, é hora de sair em {stop}",
            getOffStop = "Saia agora em {stop}", getOffNone = "Saia agora",
            changeHere = "Mude aqui para {x}",
            connectionAtRisk = "A sua ligação com {line} está em risco",
            connectionMissed = "Pode ter perdido {line}. Pode planear novamente",
            offPlan = "Está fora do itinerário previsto. Pode planear novamente", arrived = "Chegou",
        ),
        alerts = AlertPromptPhrases(
            fixedCamera = "possível radar fixo", section = "troço com controlo de velocidade média",
            mobileZone = "zona com possíveis radares móveis", v16 = "veículo imobilizado com luz V16", accident = "acidente",
            closure = "estrada cortada", congestion = "trânsito lento", obstacle = "obstáculo na via",
            limit = ". Limite {n}", slowDown = ". Reduza a velocidade",
        ),
        zbeAhead = "{lead}, zona de baixas emissões. Consulte as regras de acesso",
    )

    // ----------------------------------------------------------------------------------------------- Italian

    val ITALIAN = VoicePack(
        instructions = InstructionPhrases(
            now = "ora", testIntro = "Guida vocale attivata", and = "e",
            distanceLead = "Tra {n} {u}", meters = "metri", kilometerOne = "chilometro", kilometers = "chilometri",
            feet = "piedi", mileOne = "miglio", miles = "miglia",
            depart = sp("inizia il percorso", "inizia il percorso su {s}"),
            straight = sp("prosegui dritto", "prosegui dritto su {s}"),
            slightRight = sp("svolta leggermente a destra", "svolta leggermente a destra in {s}"),
            right = sp("svolta a destra", "svolta a destra in {s}"),
            sharpRight = sp("svolta decisa a destra", "svolta decisa a destra in {s}"),
            slightLeft = sp("svolta leggermente a sinistra", "svolta leggermente a sinistra in {s}"),
            left = sp("svolta a sinistra", "svolta a sinistra in {s}"),
            sharpLeft = sp("svolta decisa a sinistra", "svolta decisa a sinistra in {s}"),
            uTurnLeft = "fai inversione a U a sinistra", uTurnRight = "fai inversione a U a destra",
            roundaboutEnter = "entra nella rotatoria", roundaboutTake = "alla rotatoria, prendi {x}",
            exitOrdinals = listOf(
                "la prima uscita", "la seconda uscita", "la terza uscita", "la quarta uscita", "la quinta uscita",
                "la sesta uscita", "la settima uscita", "l'ottava uscita", "la nona uscita", "la decima uscita",
            ),
            exitNumber = "l'uscita numero {n}",
            roundaboutLeave = sp("esci dalla rotatoria", "esci dalla rotatoria verso {s}"), toward = " verso {s}",
            merge = sp("immettiti", "immettiti in {s}"),
            exitLeft = "prendi l'uscita a sinistra", exitRight = "prendi l'uscita a destra", exitStraight = "prendi l'uscita",
            exitRefLeft = "prendi l'uscita {ref} a sinistra", exitRefRight = "prendi l'uscita {ref} a destra",
            exitRefStraight = "prendi l'uscita {ref}",
            arriveNow = "Sei arrivato a destinazione", arriveNowSide = "Sei arrivato a destinazione, {side}",
            arriveSoon = "{lead}, arriverai a destinazione", arriveSoonSide = "{lead}, la tua destinazione sarà {side}",
            sideLeft = "a sinistra", sideRight = "a destra",
            laneLeftOne = "Mantieni la corsia di sinistra", laneLeftMany = "Mantieni le {n} corsie di sinistra",
            laneRightOne = "Mantieni la corsia di destra", laneRightMany = "Mantieni le {n} corsie di destra",
            laneCenter = "Mantieni la corsia centrale", laneFromLeft = "Mantieni la {ord} corsia da sinistra",
            laneOrdinals = listOf("seconda", "terza", "quarta", "quinta"),
            recalculating = "Ricalcolo in corso", offRoute = "Hai lasciato il percorso",
            arrived = "Sei arrivato a destinazione", stopReached = "Tappa raggiunta",
        ),
        transit = TransitPromptPhrases(
            lineNext = "il prossimo servizio", lineNamed = "la linea {line}", lineTowards = "{line} in direzione {to}",
            boardNow = "Sali ora: {x}",
            getReadyEstimated = "Secondo l'orario stai per arrivare a {stop}. Preparati a scendere",
            getReadyStop = "Preparati a scendere a {stop}, la prossima fermata",
            getReadyNone = "Preparati a scendere alla prossima fermata",
            getOffEstimated = "Secondo l'orario è il momento di scendere a {stop}",
            getOffStop = "Scendi ora a {stop}", getOffNone = "Scendi ora",
            changeHere = "Cambia qui: {x}",
            connectionAtRisk = "La tua coincidenza con {line} è a rischio",
            connectionMissed = "Potresti aver perso {line}. Puoi pianificare di nuovo",
            offPlan = "Sei fuori dall'itinerario previsto. Puoi pianificare di nuovo", arrived = "Sei arrivato",
        ),
        alerts = AlertPromptPhrases(
            fixedCamera = "possibile autovelox fisso", section = "tratto con controllo della velocità media",
            mobileZone = "zona con possibili autovelox mobili", v16 = "veicolo fermo con luce V16", accident = "incidente",
            closure = "strada chiusa", congestion = "traffico lento", obstacle = "ostacolo sulla strada",
            limit = ". Limite {n}", slowDown = ". Rallenta",
        ),
        zbeAhead = "{lead}, zona a basse emissioni. Controlla le regole di accesso",
    )
}
