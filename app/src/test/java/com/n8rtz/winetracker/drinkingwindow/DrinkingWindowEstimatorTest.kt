package com.n8rtz.winetracker.drinkingwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrinkingWindowEstimatorTest {

    private fun estimate(
        type: String? = "Red", country: String? = null, region: String? = null, subregion: String? = null,
        maker: String? = null, name: String? = null, vintage: String? = "2020"
    ) = DrinkingWindowEstimator.estimate(
        WineDescription(type, country, region, subregion, maker, name, vintage)
    )

    // The owner's Rhône trio: the same region field, three very different wines.
    @Test
    fun rhoneTrio_sameRegion_differentWindows() {
        val villages = estimate(country = "France", region = "Rhone", maker = "Kirkland Signature",
            name = "Cotes Du Rhone Villages", vintage = "2024")!!
        val vacqueyras = estimate(country = "France", region = "Rhone", maker = "Kirkland Signature",
            name = "Vacqueyras", vintage = "2023")!!
        val cdp = estimate(country = "France", region = "Rhone", maker = "Kirkland Signature",
            name = "Chateauneuf-du-Pape", vintage = "2023")!!

        assertEquals("rhone_cdr_villages", villages.profileId)
        assertEquals(2026 to 2030, villages.startYear to villages.endYear)
        assertEquals("rhone_south_cru", vacqueyras.profileId)
        assertEquals(2026 to 2033, vacqueyras.startYear to vacqueyras.endYear)
        assertEquals("rhone_cdp_red", cdp.profileId)
        assertEquals(2028 to 2038, cdp.startYear to cdp.endYear)
        assertTrue(villages.endYear < vacqueyras.endYear && vacqueyras.endYear < cdp.endYear)
    }

    @Test
    fun accentsAndPunctuation_doNotMatter() {
        val w = estimate(name = "Châteauneuf-du-Pape", region = "Rhône")!!
        assertEquals("rhone_cdp_red", w.profileId)
    }

    @Test
    fun rhoneRegionOnly_fallsBackToRegionRule() {
        val w = estimate(region = "Rhone Valley", name = "Reserve Red")!!
        assertEquals("rhone_generic_red", w.profileId)
        assertEquals(MatchLevel.REGION_AND_GRAPE, w.level)
    }

    @Test
    fun rioja_designationsChangeTheWindow() {
        val plain = estimate(region = "Rioja", name = "Tempranillo")!!
        val crianza = estimate(region = "Rioja", name = "Crianza")!!
        val reserva = estimate(maker = "Kirkland Signature", name = "Rioja Reserva")!!
        val gran = estimate(region = "Rioja", name = "Gran Reserva")!!
        assertEquals("rioja_generic", plain.profileId)
        assertEquals("rioja_crianza", crianza.profileId)
        assertEquals("rioja_reserva", reserva.profileId)
        assertEquals("rioja_gran_reserva", gran.profileId)
        assertTrue(plain.endYear < crianza.endYear)
        assertTrue(crianza.endYear < reserva.endYear)
        assertTrue(reserva.endYear < gran.endYear)
    }

    @Test
    fun pauillac_beatsGenericBordeaux() {
        val w = estimate(subregion = "Pauillac", region = "Bordeaux", country = "France", vintage = "2023")!!
        assertEquals("bdx_left_bank", w.profileId)
        assertEquals(2029 to 2038, w.startYear to w.endYear)
    }

    @Test
    fun cadillacWhite_isNotTreatedAsRedCotesDeBordeaux() {
        val red = estimate(type = "Red", region = "Bordeaux", subregion = "Cadillac")!!
        val white = estimate(type = "White", region = "Bordeaux", subregion = "Cadillac")!!
        assertEquals("bdx_cotes", red.profileId)
        assertEquals("type_white", white.profileId)
    }

    @Test
    fun misspelledRegion_andUsStateInCountryField() {
        val oregon = estimate(country = "Oregon", region = "Williamette Valley", name = "Pinot Noir")!!
        assertEquals("willamette_pinot", oregon.profileId)
        val napa = estimate(country = "California", region = "Napa valley", name = "Cabernet Sauvignon")!!
        assertEquals("napa_cabernet", napa.profileId)
        val tuscan = estimate(country = "Italy", region = "Toscana", subregion = "Chianti Classico", name = "Riserva")!!
        assertEquals("chianti_classico_riserva", tuscan.profileId)
    }

    @Test
    fun mosel_praedikatMatters() {
        val spat = estimate(type = "White", region = "Mosel", maker = "Thanisch", name = "Riesling Spätlese", vintage = "2016")!!
        val kab = estimate(type = "White", region = "Mosel", name = "Riesling Kabinett", vintage = "2016")!!
        assertEquals("riesling_spatlese", spat.profileId)
        assertEquals(2021 to 2031, spat.startYear to spat.endYear)
        assertEquals("riesling_kabinett", kab.profileId)
        assertEquals("riesling_mosel", estimate(type = "White", region = "Mosel", name = "Riesling")!!.profileId)
    }

    @Test
    fun chablis_tiers() {
        assertEquals("chablis_premier_cru", estimate(type = "White", name = "Chablis 1er Cru Montmains")!!.profileId)
        assertEquals("chablis_petit", estimate(type = "White", name = "Petit Chablis")!!.profileId)
        assertEquals("chablis", estimate(type = "White", name = "Chablis")!!.profileId)
    }

    @Test
    fun reservaBonus_appliesOnlyWhereTheProfileAllowsIt() {
        val plain = estimate(country = "Portugal", region = "Dao", vintage = "2018")!!
        val reserva = estimate(country = "Portugal", region = "Dao", name = "Reserva", vintage = "2018")!!
        assertEquals(plain.endYear + 5, reserva.endYear)
        val reserve = estimate(country = "Portugal", region = "Dao", name = "Reserve", vintage = "2018")!!
        assertEquals(plain.endYear, reserve.endYear) // "Reserve" is a brand word, not a designation
    }

    @Test
    fun nonVintageOrMissingVintage_hasNoEstimate() {
        assertNull(estimate(name = "Chateauneuf du Pape", vintage = "NV"))
        assertNull(estimate(name = "Chateauneuf du Pape", vintage = ""))
        assertNull(estimate(name = "Chateauneuf du Pape", vintage = null))
        assertNull(estimate(name = "Chateauneuf du Pape", vintage = "0"))
        assertEquals(2025, DrinkingWindowEstimator.parseVintage(" (2025) "))
    }

    @Test
    fun typeOnly_getsEstimateByType() {
        val w = estimate(type = "Red", name = "Mystery Blend", maker = "Unknown", vintage = "2021")!!
        assertEquals(MatchLevel.TYPE, w.level)
        assertEquals("Estimate by type", w.note)
        assertEquals(2024 to 2029, w.startYear to w.endYear)
        assertEquals("type_rose", estimate(type = "Rosé", name = "Pink", vintage = "2024")!!.profileId)
    }

    @Test
    fun unknownType_andNothingMatched_hasNoEstimate() {
        assertNull(estimate(type = null, name = "Mystery", vintage = "2021"))
        assertNull(estimate(type = "Orange", name = "Mystery", vintage = "2021"))
    }

    @Test
    fun specificNote_namesTheRule() {
        val w = estimate(name = "Chateauneuf du Pape", vintage = "2020")!!
        assertEquals("Estimate: Châteauneuf-du-Pape red", w.note)
        assertEquals(false, w.reviewed)
    }

    @Test
    fun table_isWellFormed() {
        val ids = AgeingProfiles.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        AgeingProfiles.all.forEach { p ->
            assertTrue("${p.id} start <= end", p.startYears in 0..p.endYears)
            assertTrue("${p.id} needs a source note", p.source.isNotBlank())
            assertTrue("${p.id} ships unreviewed", !p.reviewed)
            if (p.level == MatchLevel.TYPE) {
                assertTrue("${p.id} type rule has a type", p.type != null && p.groups.isEmpty())
            } else {
                assertTrue("${p.id} has keywords", p.groups.isNotEmpty() && p.groups.all { it.isNotEmpty() })
            }
        }
        listOf("red", "white", "rose", "sparkling", "dessert").forEach { t ->
            assertNotNull("type fallback for $t", AgeingProfiles.all.firstOrNull { it.level == MatchLevel.TYPE && it.type == t })
        }
    }
}
