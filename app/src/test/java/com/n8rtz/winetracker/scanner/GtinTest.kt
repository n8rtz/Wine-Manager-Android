package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GtinTest {
    @Test
    fun acceptsRealEanUpcAndEan8Codes() {
        assertTrue("EAN-13", Gtin.isValid("4006381333931"))
        assertTrue("UPC-A", Gtin.isValid("036000291452"))
        assertTrue("EAN-8", Gtin.isValid("96385074"))
        assertTrue("GTIN-14", Gtin.isValid("04006381333931"))
    }

    @Test
    fun rejectsACodeWithABrokenCheckDigit() {
        assertFalse(Gtin.isValid("4006381333932"))
        assertFalse(Gtin.isValid("036000291453"))
        assertFalse(Gtin.isValid("96385075"))
    }

    @Test
    fun rejectsCodesThatAreNotGtinLengths() {
        assertNull(Gtin.normalize("123456"))
        assertNull(Gtin.normalize("123456789"))
        assertNull(Gtin.normalize("123456789012345"))
        assertFalse(Gtin.isValid("123456"))
        assertFalse(Gtin.isValid(""))
    }

    @Test
    fun rejectsNonNumericReads() {
        assertFalse(Gtin.isValid("40063813339AB"))
        assertFalse(Gtin.isValid("hello"))
        assertFalse(Gtin.isValid(null))
    }

    @Test
    fun stripsSpacingFromAMultipleFieldRead() {
        // Some scanners return a UPC-A wrapped in the EAN-13 "0" prefix and spaces.
        assertEquals("036000291452", Gtin.normalize("0 36000291452"))
        assertTrue(Gtin.isValid("0 36000291452"))
    }
}
