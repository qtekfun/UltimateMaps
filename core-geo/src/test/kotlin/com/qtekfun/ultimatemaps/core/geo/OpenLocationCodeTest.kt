package com.qtekfun.ultimatemaps.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The vectors below are rows of the official test data of the Open Location Code project (Apache-2.0), files
 * `encoding.csv`, `decoding.csv`, `shortCodeTests.csv` and `validityTests.csv`, copied as data in this session.
 * Only a selection is included (the whole files have several hundred random rows).
 */
class OpenLocationCodeTest {
    // lat, lon, length, code
    private val encoding = listOf(
        "20.375,2.775,6,7FG49Q00+",
        "20.3700625,2.7821875,10,7FG49QCJ+2V",
        "20.3701125,2.782234375,11,7FG49QCJ+2VX",
        "20.3701135,2.78223535156,13,7FG49QCJ+2VXGJ",
        "47.0000625,8.0000625,10,8FVC2222+22",
        "-41.2730625,174.7859375,10,4VCPPQGP+Q9",
        "0.5,-179.5,4,62G20000+",
        "-89.5,-179.5,4,22220000+",
        "20.5,2.5,4,7FG40000+",
        "-89.9999375,-179.9999375,10,22222222+22",
        "0.5,179.5,4,6VGX0000+",
        "1,1,11,6FH32222+222",
        "90,1,4,CFX30000+",
        "92,1,4,CFX30000+",
        "90,1,10,CFX3X2X2+X2",
        "1,180,4,62H20000+",
        "1,181,4,62H30000+",
        "20.3701135,362.78223535156,13,7FG49QCJ+2VXGJ",
        "47.0000625,728.0000625,10,8FVC2222+22",
        "-41.2730625,1254.7859375,10,4VCPPQGP+Q9",
        "20.3701135,-357.217764648,13,7FG49QCJ+2VXGJ",
        "47.0000625,-711.9999375,10,8FVC2222+22",
        "-41.2730625,-905.2140625,10,4VCPPQGP+Q9",
        "1.2,3.4,10,6FH56C22+22",
        "37.539669125,-122.375069724,15,849VGJQF+VX7QR3J",
        "37.539669125,-122.375069724,16,849VGJQF+VX7QR3J",
        "37.539669125,-122.375069724,100,849VGJQF+VX7QR3J",
        "35.6,3.033,10,8F75J22M+26",
        "-48.71,142.78,8,4R347QRJ+",
        "-70,163.7,8,3V252P22+",
        "-2.804,7.003,13,6F9952W3+C6222",
        "13.9,164.88,12,7V56WV2J+2222",
        "-13.23,172.77,8,5VRJQQCC+",
        "-52.166,13.694,14,3FVMRMMV+JJ2222",
        "70.3,-87.64,13,C62J8926+22222",
        "80.0100000001,58.57,15,CHGW2H6C+2222222",
        "80.00999996,58.57,15,CHGW2H5C+X2RRRRR",
        "-80.0099999999,58.57,15,2HFWXHRC+2222222",
        "-80.0100000399,58.57,15,2HFWXHQC+X2RRRRR",
        "68.3500147997595,113.625636875353,15,9PWM9J2G+272FWJV",
        "-28.1217794010122,-154.066811473758,15,5337VWHM+77PR2GR",
        "37.539669125,-122.375069724,2,84000000+",
        "51.1276857,-184.2279861,11,9V3Q4QHC+3RC",
        "-93.84140,-162.06820,10,222V2W2J+2P",
        "98.31,86.17,11,CMX8X5XC+X2R",
    ).map { it.split(',') }

    @Test
    fun encodesLikeThePublishedVectors() {
        for ((lat, lon, len, code) in encoding) {
            assertEquals(code, OpenLocationCode.encode(lat.toDouble(), lon.toDouble(), len.toInt()), "encode $lat,$lon,$len")
        }
    }

    @Test
    fun encodesALatLonValue() {
        // 47.0000625, 8.0000625 is inside the cell 8FVC2222+22 (from the encoding vectors).
        assertEquals("8FVC2222+22", OpenLocationCode.encode(LatLon(47.0000625, 8.0000625)))
    }

    // code, length, latLo, lonLo, latHi, lonHi
    private val decoding = listOf(
        "7FG49Q00+,6,20.35,2.75,20.4,2.8",
        "7FG49QCJ+2V,10,20.37,2.782125,20.370125,2.78225",
        "7FG49QCJ+2VX,11,20.3701,2.78221875,20.370125,2.78225",
        "7FG49QCJ+2VXGJ,13,20.370113,2.782234375,20.370114,2.78223632813",
        "8FVC2222+22,10,47.0,8.0,47.000125,8.000125",
        "4VCPPQGP+Q9,10,-41.273125,174.785875,-41.273,174.786",
        "62G20000+,4,0.0,-180.0,1,-179",
        "22220000+,4,-90,-180,-89,-179",
        "22222222+22,10,-90.0,-180.0,-89.999875,-179.999875",
        "6VGX0000+,4,0,179,1,180",
        "6FH32222+222,11,1,1,1.000025,1.00003125",
        "CFX30000+,4,89,1,90,2",
        "CFX3X2X2+X2,10,89.9998750,1,90,1.0001250",
        "84000000+,2,30,-140,50,-120",
        "849VGJQF+VX7QR3J,15,37.5396691200,-122.3750698242,37.5396691600,-122.3750697021",
        "849VGJQF+VX7QR3J7QR3J,15,37.5396691200,-122.3750698242,37.5396691600,-122.3750697021",
        "55CJG366+PP,10,-21.48825,-107.93825,-21.488125,-107.938125",
        "4VXPHWHV+,8,-30.4225,174.9425,-30.42,174.945",
    ).map { it.split(',') }

    @Test
    fun decodesLikeThePublishedVectors() {
        for (row in decoding) {
            val code = row[0]
            val a = OpenLocationCode.decode(code)
            assertEquals(row[1].toInt(), a.codeLength, code)
            assertEquals(row[2].toDouble(), a.latLo, 1e-10, "$code latLo")
            assertEquals(row[3].toDouble(), a.lonLo, 1e-10, "$code lonLo")
            assertEquals(row[4].toDouble(), a.latHi, 1e-10, "$code latHi")
            assertEquals(row[5].toDouble(), a.lonHi, 1e-10, "$code lonHi")
        }
    }

    @Test
    fun centreOfADecodedCodeEncodesBackToTheSameCode() {
        for (code in listOf("7FG49QCJ+2V", "8FVC2222+22", "4VCPPQGP+Q9", "849VGJQF+VX7QR3J")) {
            val a = OpenLocationCode.decode(code)
            assertEquals(code, OpenLocationCode.encode(a.center, a.codeLength))
        }
    }

    // full code, reference lat, reference lon, short code, type (R recover only, S shorten only, B both)
    private val shortTests = listOf(
        "9C3W9QCJ+2VX,51.3701125,-1.217765625,+2VX",
        "9C3W9QCJ+2VX,51.3708675,-1.217765625,CJ+2VX",
        "9C3W9QCJ+2VX,51.3693575,-1.217765625,CJ+2VX",
        "9C3W9QCJ+2VX,51.3701125,-1.218520625,CJ+2VX",
        "9C3W9QCJ+2VX,51.3701125,-1.217010625,CJ+2VX",
        "9C3W9QCJ+2VX,51.3852125,-1.217765625,9QCJ+2VX",
        "9C3W9QCJ+2VX,51.3550125,-1.217765625,9QCJ+2VX",
        "9C3W9QCJ+2VX,51.3701125,-1.232865625,9QCJ+2VX",
        "9C3W9QCJ+2VX,51.3701125,-1.202665625,9QCJ+2VX",
        "8FJFW222+,42.899,9.012,22+",
        "796RXG22+,14.95125,-23.5001,22+",
        "8FVC2GGG+GG,46.976,8.526,2GGG+GG",
        "8FRCXGGG+GG,47.026,8.526,XGGG+GG",
        "8FR9GXGG+GG,46.526,8.026,GXGG+GG",
        "8FRCG2GG+GG,46.526,7.976,G2GG+GG",
        "CFX22222+22,89.6,0.0,2222+22",
        "2CXXXXXX+XX,-81.0,0.0,XXXXXX+XX",
        "8FRCG2GG+GG,46.526,7.976,8FRCG2GG+GG",
        "8FRCG2GG+GG,46.526,7.976,8frCG2GG+gG",
    ).map { it.split(',') }

    @Test
    fun recoversShortCodesNearTheReference() {
        for ((full, lat, lon, short) in shortTests) {
            val got = OpenLocationCode.recoverNearest(short, LatLon(lat.toDouble(), lon.toDouble()))
            assertEquals(full, got, "recover $short near $lat,$lon")
        }
    }

    // code, valid, short, full
    private val validity = listOf(
        "8FWC2345+G6,true,false,true",
        "8FWC2345+G6G,true,false,true",
        "8fwc2345+,true,false,true",
        "8FWCX400+,true,false,true",
        "84000000+,true,false,true",
        "WC2345+G6g,true,true,false",
        "2345+G6,true,true,false",
        "45+G6,true,true,false",
        "+G6,true,true,false",
        "G+,false,false,false",
        "+,false,false,false",
        "8FWC2345+G,false,false,false",
        "8FWC2_45+G6,false,false,false",
        "8FWC2η45+G6,false,false,false",
        "8FWC2345+G6+,false,false,false",
        "8FWC2345G6+,false,false,false",
        "8FWC2300+G6,false,false,false",
        "WC2300+G6g,false,false,false",
        "WC2345+G,false,false,false",
        "WC2300+,false,false,false",
        "84900000+,false,false,false",
        "849VGJQF+VX7QR3J,true,false,true",
        "849VGJQF+VX7QR3U,false,false,false",
        "849VGJQF+VX7QR3JW,true,false,true",
        "849VGJQF+VX7QR3JU,false,false,false",
    ).map { it.split(',') }

    @Test
    fun validatesLikeThePublishedVectors() {
        for ((code, valid, short, full) in validity) {
            assertEquals(valid.toBoolean(), OpenLocationCode.isValid(code), "valid $code")
            assertEquals(short.toBoolean(), OpenLocationCode.isShort(code), "short $code")
            assertEquals(full.toBoolean(), OpenLocationCode.isFull(code), "full $code")
        }
    }

    @Test
    fun decodeRejectsShortAndInvalidCodes() {
        assertFailsWith<IllegalArgumentException> { OpenLocationCode.decode("2345+G6") }
        assertFailsWith<IllegalArgumentException> { OpenLocationCode.decode("hello") }
        assertFailsWith<IllegalArgumentException> { OpenLocationCode.recoverNearest("hello", LatLon(0.0, 0.0)) }
    }

    @Test
    fun everyCodeIsAValidFullCode() {
        for (lat in listOf(-90.0, -45.5, 0.0, 40.4168, 89.999)) for (lon in listOf(-180.0, -3.7038, 0.0, 179.9999)) {
            val code = OpenLocationCode.encode(LatLon(lat, lon))
            assertTrue(OpenLocationCode.isFull(code), code)
            assertFalse(OpenLocationCode.isShort(code), code)
        }
    }
}
