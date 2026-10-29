package com.scrobblecue.app.data

import java.text.Normalizer

/*
 * Display helpers for Discogs names. The backend applies the same rules to tracklists; the app needs
 * them for the collection list, which comes from Discogs unchanged. Same as web/src/lib/names.ts.
 */

/** Discogs disambiguation suffix, e.g. "Prince (2)". */
private val DISAMBIGUATION = Regex("""\s+\(\d+\)$""")
private val WHITESPACE = Regex("""\s+""")

/** Removes Discogs' "(2)"-style suffixes and extra whitespace. */
fun cleanName(name: String?): String =
    (name ?: "").trim().replace(WHITESPACE, " ").replace(DISAMBIGUATION, "")

/** One artist credit as Discogs sends it: the name and how it joins the next one ("&", "Feat."). */
data class ArtistCredit(val name: String, val join: String? = null)

/** Joins Discogs artist credits for display: "A & B", "A Feat. B", "A, B". */
fun joinArtists(artists: List<ArtistCredit>): String {
    if (artists.isEmpty()) return ""
    val joined = artists.mapIndexed { i, artist ->
        val name = cleanName(artist.name)
        if (i == artists.lastIndex) return@mapIndexed name
        val join = artist.join.orEmpty().trim()
        if (join.isEmpty() || join == ",") "$name, " else "$name $join "
    }.joinToString("")
    return if (joined == "Various") "Various Artists" else joined
}

/** Lower-case, accent-free form for searching ("Thérace" matches "therace"). */
fun searchable(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "").lowercase()
