package com.qtekfun.ultimatemaps.nativecomaps

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.PlaceExtras
import com.qtekfun.ultimatemaps.core.search.SearchResult
import com.qtekfun.ultimatemaps.core.search.Wheelchair
import com.qtekfun.ultimatemaps.nativecomaps.isolation.CoreProtocol
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SearchWireTest {
    private val v1 = arrayOf("Cafe Sol", "Calle Mayor 1", "cafe", "40.1", "-3.2")
    private val v2Full = arrayOf(
        "Farmacia", "", "pharmacy", "40.5", "-3.5", "+34 912 345 678;+34 600 000 000", "example.org", "limited", "Mo-Fr 09:00-20:00",
    )
    private val v2Empty = arrayOf("Plaza", "", "", "41.0", "-4.0", "", "", "", "")

    @Test fun `the current layout is version 2 with stride 9`() {
        assertEquals(SearchWire.V2, SearchWire.VERSION)
        assertEquals(9, SearchWire.stride(SearchWire.VERSION))
        assertEquals(5, SearchWire.stride(SearchWire.V1))
        assertFailsWith<IllegalArgumentException> { SearchWire.stride(99) }
    }

    @Test fun `the old five-field layout still decodes, without extras`() {
        val r = decodeSearch(v1, SearchWire.V1).single()
        assertEquals("Cafe Sol", r.name)
        assertEquals(LatLon(40.1, -3.2), r.point)
        assertEquals("Calle Mayor 1", r.address)
        assertEquals("cafe", r.category)
        assertNull(r.extras)
    }

    @Test fun `the new nine-field layout fills the extras`() {
        val r = decodeSearch(v2Full).single()
        assertNull(r.address)
        assertEquals(
            PlaceExtras("+34 912 345 678;+34 600 000 000", "example.org", Wheelchair.LIMITED, "Mo-Fr 09:00-20:00"),
            r.extras,
        )
    }

    @Test fun `a place without tags has no extras at all`() {
        assertNull(decodeSearch(v2Empty).single().extras)
    }

    @Test fun `several results decode in order and a stride mismatch is rejected`() {
        assertEquals(listOf("Farmacia", "Plaza"), decodeSearch(v2Full + v2Empty).map { it.name })
        assertFailsWith<IllegalArgumentException> { decodeSearch(v1 + v1) } // 10 strings is not 9-aligned...
        assertEquals(2, decodeSearch(v1 + v1, SearchWire.V1).size) // ...but is a valid v1 reply
        assertFailsWith<IllegalArgumentException> { decodeSearch(v2Full.copyOf(8).requireNoNulls()) }
    }

    @Test fun `an unknown wheelchair value is ignored, not an error`() {
        val raw = v2Full.copyOf().also { it[7] = "maybe" }
        assertNull(decodeSearch(raw).single().extras?.wheelchair)
    }

    // ---- binder protocol ----

    private val sample = listOf(
        SearchResult("Farmacia", LatLon(40.5, -3.5), null, "pharmacy", extras = PlaceExtras("123", "https://x.org", Wheelchair.YES, "24/7")),
        SearchResult("Plaza", LatLon(41.0, -4.0), "Calle 2", null),
    )

    @Test fun `the binder reply round trips the extras`() {
        val back = CoreProtocol.decodeSearchResults(CoreProtocol.encodeSearchResults(sample))
        assertEquals(sample, back)
    }

    @Test fun `a reply from the previous protocol version still decodes, without extras`() {
        val old = CoreProtocol.encodeSearchResults(sample, version = 1)
        val back = CoreProtocol.decodeSearchResults(old)
        assertEquals(sample.map { it.copy(extras = null) }, back)
    }

    @Test fun `an unknown future reply version is refused cleanly`() {
        val bytes = CoreProtocol.write { it.writeInt(-7); it.writeInt(0) }
        assertFailsWith<IOException> { CoreProtocol.decodeSearchResults(bytes) }
    }
}
