package fr.spotifeur.utils

import java.text.Normalizer

private val MARKS = Regex("\\p{Mn}+")

/** Lower case without accents, for comparing what someone typed with titles and names. */
fun String.foldForSearch(): String = Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

/** Every word of [query] appears in one of [fields], ignoring case and accents. A blank query matches. */
fun matchesWords(query: String, vararg fields: String): Boolean {
    val words = query.foldForSearch().split(' ').filter(String::isNotBlank)
    if (words.isEmpty()) return true
    val text = fields.joinToString(" ").foldForSearch()
    return words.all(text::contains)
}
