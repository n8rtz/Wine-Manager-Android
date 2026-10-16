package com.n8rtz.winetracker.drinkingwindow

import java.text.Normalizer
import java.util.Locale

/**
 * How specific the matched ageing rule was. Higher beats lower; the estimator always picks the
 * most specific rule that matches the wine.
 */
enum class MatchLevel(val rank: Int) {
    /** Wine type only (Red/White/...). Shown to the owner as "Estimate by type". */
    TYPE(0),

    /** A grape or style found in the name, with no place. */
    GRAPE(1),

    /** A region plus a grape/type (for example Napa + Cabernet, Mosel + Riesling). */
    REGION_AND_GRAPE(2),

    /** A named appellation, cru or designation (Châteauneuf-du-Pape, Rioja Reserva, Spätlese...). */
    APPELLATION(3)
}

/** What the estimator may look at. All fields are free text from the wine record. */
data class WineDescription(
    val type: String? = null,
    val country: String? = null,
    val region: String? = null,
    val subregion: String? = null,
    val maker: String? = null,
    val name: String? = null,
    val vintage: String? = null
)

/** A computed, display-only window. Never stored and never used by Cellar filters or sorts. */
data class EstimatedWindow(
    val startYear: Int,
    val endYear: Int,
    val level: MatchLevel,
    val label: String,
    val profileId: String,
    val reviewed: Boolean
) {
    /**
     * Text shown after the years. Only the weak case needs a warning: a type-only estimate says so.
     * A specific match shows just the years; [label] (which may name a grape) is for logs and tests.
     */
    val note: String?
        get() = if (level == MatchLevel.TYPE) "Estimate by type" else null
}

/**
 * Offline drinking-window estimator (W-050 Phase 1).
 *
 * Pure Kotlin: no Android, no clock, no I/O. Matching is done over the maker, name, subregion,
 * region and country together, so it still works when the location fields are inconsistent
 * (US states stored as the country, spelling variants such as "Williamette").
 */
object DrinkingWindowEstimator {

    fun estimate(wine: WineDescription): EstimatedWindow? {
        val vintage = parseVintage(wine.vintage) ?: return null // non-vintage: nothing to count from
        val type = normalizeText(wine.type).takeIf { it.isNotEmpty() }
        val haystack = listOf(wine.maker, wine.name, wine.subregion, wine.region, wine.country)
            .map { normalizeText(it) }
            .filter { it.isNotEmpty() }
            .joinToString(separator = " | ", prefix = " | ", postfix = " | ") // "|" stops phrases matching across fields

        var best: AgeingProfile? = null
        var bestScore = -1
        for (profile in AgeingProfiles.all) {
            val score = matchScore(profile, haystack, type) ?: continue
            val better = best == null ||
                profile.level.rank > best.level.rank ||
                (profile.level.rank == best.level.rank && score > bestScore)
            if (better) {
                best = profile
                bestScore = score
            }
        }
        val profile = best ?: return null

        var end = vintage + profile.endYears
        if (profile.reservaBonusYears > 0 &&
            (haystack.contains(" reserva ") || haystack.contains(" riserva "))
        ) {
            end += profile.reservaBonusYears
        }
        return EstimatedWindow(
            startYear = vintage + profile.startYears,
            endYear = end,
            level = profile.level,
            label = profile.label,
            profileId = profile.id,
            reviewed = profile.reviewed
        )
    }

    /** Null when the profile does not apply; otherwise a tie-break score (matched text length). */
    private fun matchScore(profile: AgeingProfile, haystack: String, type: String?): Int? {
        if (profile.type != null && profile.type != type) return null
        var score = 0
        for (group in profile.normalizedGroups) {
            val hit = group.filter { haystack.contains(" $it ") }.maxOfOrNull { it.length } ?: return null
            score += hit
        }
        return score
    }

    internal fun parseVintage(raw: String?): Int? {
        val year = raw?.trim()?.removeSurrounding("(", ")")?.trim()?.toIntOrNull() ?: return null
        return year.takeIf { it in 1900..2100 }
    }
}

/** Lower-case, accent-free, punctuation-free text so "Châteauneuf-du-Pape" == "chateauneuf du pape". */
internal fun normalizeText(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val stripped = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    return stripped.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
}
