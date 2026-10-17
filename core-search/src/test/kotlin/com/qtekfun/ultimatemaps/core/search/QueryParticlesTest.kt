package com.qtekfun.ultimatemaps.core.search

import com.qtekfun.ultimatemaps.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryParticlesTest {
    private fun r(name: String) = SearchResult(name, LatLon(40.0, -3.0))

    @Test
    fun linkingWordsAreRemoved() {
        assertEquals("plaza espana", QueryParticles.withoutParticles("plaza de espana"))
        assertEquals("Puerta Sol", QueryParticles.withoutParticles("Puerta del Sol")) // the other words keep their spelling
        assertEquals("calle alcalá", QueryParticles.withoutParticles("calle de alcalá"))
        assertEquals("rue Paris", QueryParticles.withoutParticles("rue de Paris"))
        assertEquals("tower london", QueryParticles.withoutParticles("tower of london"))
    }

    @Test
    fun aTrailingWordBeingTypedIsRemovedToo() {
        assertEquals("plaza", QueryParticles.withoutParticles("plaza de"))
        assertEquals("plaza", QueryParticles.withoutParticles("plaza del "))
    }

    @Test
    fun otherWordsAndNumbersStay() {
        assertEquals("gran via 30", QueryParticles.withoutParticles("gran via de 30"))
        assertEquals("calle mayor 5", QueryParticles.withoutParticles("calle de la mayor 5"))
    }

    @Test
    fun aTextWithoutLinkingWordsIsLeftAlone() {
        assertNull(QueryParticles.withoutParticles("plaza espana"))
        assertNull(QueryParticles.withoutParticles("gran via 30"))
        assertNull(QueryParticles.withoutParticles("farmacia"))
        assertNull(QueryParticles.withoutParticles(""))
    }

    @Test
    fun aTextOfOnlyLinkingWordsOrWithNothingToSearchIsLeftAlone() {
        assertNull(QueryParticles.withoutParticles("de"))
        assertNull(QueryParticles.withoutParticles("de la"))
        assertNull(QueryParticles.withoutParticles("de x"))
    }

    @Test
    fun partsOfWordsAreNotLinkingWords() {
        assertNull(QueryParticles.withoutParticles("delicias madrid"))
        assertNull(QueryParticles.withoutParticles("dela plaza"))
        assertEquals("madrid", QueryParticles.withoutParticles("madrid de"))
    }

    @Test
    fun rerankPutsTheNamesWithTheTypedPhraseFirst() {
        val results = listOf(r("Paseo de los Pinos"), r("Paseo del Prado"), r("Parque Infantil Paseo del prado"), r("Paseo Prado Alto"))
        assertEquals(
            listOf("Paseo del Prado", "Parque Infantil Paseo del prado", "Paseo de los Pinos", "Paseo Prado Alto"),
            QueryParticles.rerank("paseo del prado", results).map { it.name },
        )
    }

    @Test
    fun rerankIgnoresAccentsCaseAndSpaces() {
        val results = listOf(r("Calle Mayor"), r("CALLE  DE ALCALÁ"))
        assertEquals(listOf("CALLE  DE ALCALÁ", "Calle Mayor"), QueryParticles.rerank("Calle de Alcala", results).map { it.name })
    }

    @Test
    fun rerankKeepsTheEnginesOrderInsideEachGroup() {
        val results = listOf(r("B x"), r("A plaza de espana"), r("C y"), r("Plaza de España"))
        assertEquals(listOf("A plaza de espana", "Plaza de España", "B x", "C y"), QueryParticles.rerank("plaza de espana", results).map { it.name })
    }

    @Test
    fun rerankLeavesShortOrEmptyInputsAlone() {
        val one = listOf(r("x"))
        assertEquals(one, QueryParticles.rerank("x", one))
        assertEquals(emptyList(), QueryParticles.rerank("x", emptyList()))
        val two = listOf(r("a"), r("b"))
        assertEquals(two, QueryParticles.rerank("  ", two))
    }
}
