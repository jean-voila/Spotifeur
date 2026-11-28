package fr.spotifeur.data

import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracks of a real 1,702-track playlist that no YouTube Music candidate matched by ear (2026-11-28),
 * with the candidates the searches returned: the recording played instead, or none when the track is
 * not on YouTube Music.
 */
class SkibidisongFallbackCasesTest {
    private class Case(val title: String, val artists: String, val seconds: Int, val first: List<Pair<Music, Double>>, val all: List<Pair<Music, Double>>,
                       val unplayable: Set<String> = emptySet()) {
        val query get() = MatchQuery("spotify:track:x", title, artists, seconds * 1000)
        fun choice(): String? = TrackMatcher.fallbackChoice(query, first, all) { it.id.removePrefix("ytm:track:") !in unplayable }?.id?.removePrefix("ytm:track:")
    }

    private fun c(id: String, score: Double, title: String, artist: String, seconds: Int) =
        Music("ytm:track:$id", title, artist, MusicKind.Track, durationMs = seconds * 1000) to score

    private val case0 = Case("What falling in love feels like", "D.A", 146,
        listOf(
            c("rcQgmdH-mFQ", 0.6, "What Falling in Love Feels Like", "Pianza", 130),
            c("BOyO8sZOaOQ", 0.56, "JVKE - this is what falling in love feels like", "JVKE", 155),
            c("E3rP41rECCY", 0.51, "this is what falling in love feels like - JVKE (Lirik Lagu Terjemahan)", "DeLirik", 131),
            c("3qLZZdYt3jQ", 0.5, "this is what falling in love feels like (Multilingual)", "Kitsui Akira", 123),
        ),
        listOf(
            c("rcQgmdH-mFQ", 0.6, "What Falling in Love Feels Like", "Pianza", 130),
            c("BOyO8sZOaOQ", 0.56, "JVKE - this is what falling in love feels like", "JVKE", 155),
            c("E3rP41rECCY", 0.51, "this is what falling in love feels like - JVKE (Lirik Lagu Terjemahan)", "DeLirik", 131),
            c("3qLZZdYt3jQ", 0.5, "this is what falling in love feels like (Multilingual)", "Kitsui Akira", 123),
            c("mIAqjGPisg0", 0.45, "JVKE – this is what falling in love feels like ft. Leon Leiden (AMV) | ¿Acaso no te has dado cuenta?", "UnPibardoNormal7", 122),
            c("XMYMPudm_p0", 0.45, "JVKE - This is what falling in love feels like (Anime AMV) | Sub Español", "DARIAN《ONGAKU》", 122),
            c("SA4_sUNtT6Q", 0.44, "JVKE - this is what falling in love feels like (sub. español)", "kar;ma", 121),
        ),
        emptySet(),
    )

    private val case1 = Case("sex seven", "Arlen Grace", 123,
        listOf(
            c("JKROWwcoDiQ", 0.64, "Six Seven", "Aldrey Lane", 120),
            c("6aA4WMUvSz0", 0.58, "SIX / SEVEN", "RHEA LUNE", 112),
            c("227rHHh3r6U", 0.46, "Sex Seven Days", "P.Hazzard", 160),
            c("TK0pVRIS8Ew", 0.26, "Penthouse SIX SEVEN", "Miss Sevena", 183),
        ),
        listOf(
            c("JKROWwcoDiQ", 0.64, "Six Seven", "Aldrey Lane", 120),
            c("6aA4WMUvSz0", 0.58, "SIX / SEVEN", "RHEA LUNE", 112),
            c("227rHHh3r6U", 0.46, "Sex Seven Days", "P.Hazzard", 160),
            c("TK0pVRIS8Ew", 0.26, "Penthouse SIX SEVEN", "Miss Sevena", 183),
        ),
        emptySet(),
    )

    private val case2 = Case("Hung Up", "Madonna", 336,
        listOf(
            c("20OGm_-RebE", 1.05, "Hung Up", "Madonna", 338),
            c("P7PlaOnZxr4", 1.05, "Hung Up", "Madonna", 337),
            c("yPqDHbTelA4", 0.65, "Hung Up (Radio Version)", "Madonna", 203),
            c("--QAhOcIwsE", 0.5, "Hung up", "Partenaire Particulier", 300),
        ),
        listOf(
            c("20OGm_-RebE", 1.05, "Hung Up", "Madonna", 338),
            c("P7PlaOnZxr4", 1.05, "Hung Up", "Madonna", 337),
            c("yPqDHbTelA4", 0.65, "Hung Up (Radio Version)", "Madonna", 203),
            c("--QAhOcIwsE", 0.5, "Hung up", "Partenaire Particulier", 300),
        ),
        emptySet(),
    )

    private val case3 = Case("?", "Jake St Ange", 106,
        listOf(
            c("i1qIjh8l-g0", 1.05, "?", "Jake St Ange", 107),
            c("_y1Q0MOUpeI", 0.05, "something in the water", "Jake St Ange", 134),
            c("1-mnT-b8yys", 0.05, "? (live version)", "Jake St Ange", 117),
            c("4UDxt0C2ty4", 0.05, "the kitchen", "Jake St Ange", 131),
        ),
        listOf(
            c("i1qIjh8l-g0", 1.05, "?", "Jake St Ange", 107),
            c("_y1Q0MOUpeI", 0.05, "something in the water", "Jake St Ange", 134),
            c("1-mnT-b8yys", 0.05, "? (live version)", "Jake St Ange", 117),
            c("4UDxt0C2ty4", 0.05, "the kitchen", "Jake St Ange", 131),
        ),
        setOf("i1qIjh8l-g0", "1-mnT-b8yys"),
    )

    private val case4 = Case("MY MAN IS ABOUT TO HIT A SPECIAL ONE", "Arlen Grace, Matilin", 146,
        listOf(
            c("1nT7UtLR3Kc", 0.05, "Grace", "Alligrah", 227),
            c("0UzXqRxPmx0", 0.05, "The Man That Got Away", "Sheena Easton", 264),
            c("mR4towgmo-4", 0.05, "Ride out My Storms", "Minister Raleigh Christie & The Effectual Praise Singers", 371),
            c("WEWPfH-WXoo", 0.05, "Give Me A Heart Like Thine", "The Grace Thrillers", 242),
        ),
        listOf(
            c("1nT7UtLR3Kc", 0.05, "Grace", "Alligrah", 227),
            c("0UzXqRxPmx0", 0.05, "The Man That Got Away", "Sheena Easton", 264),
            c("mR4towgmo-4", 0.05, "Ride out My Storms", "Minister Raleigh Christie & The Effectual Praise Singers", 371),
            c("WEWPfH-WXoo", 0.05, "Give Me A Heart Like Thine", "The Grace Thrillers", 242),
            c("-0WQ0u-H5rQ", 0.05, "Strongheaded Woman", "Billie Ray Martin", 359),
            c("pX6J_fRpSzQ", 0.05, "Only a Look (Live) (feat. Skylynn Grayer & Pleasant Stephens)", "Dave Martin & Glory Song Ministries", 301),
            c("57aaX6cn0DI", 0.05, "this one is mine (for mom)", "Gregg Allen", 195),
        ),
        emptySet(),
    )

    private val case5 = Case("Мой мармеладный (Я не права)", "Katya Lel", 223,
        listOf(
            c("NKTo3B-B4-s", 0.84, "Катя Лель - Мой мармеладный", "Katya Lel", 248),
            c("UgFVm3BTu8E", 0.83, "Катя Лель-Мой мармеладный (katya lel-moi marmeladnyi) türkçe çeviri", "sucuk", 204),
            c("_-Nf59hG1N4", 0.7, "Мой мармеладный", "Катя Лель", 224),
            c("CuttNYrYIh8", 0.7, "Мой мармеладный (Я не права)", "surround. & (((())))", 225),
        ),
        listOf(
            c("NKTo3B-B4-s", 0.84, "Катя Лель - Мой мармеладный", "Katya Lel", 248),
            c("UgFVm3BTu8E", 0.83, "Катя Лель-Мой мармеладный (katya lel-moi marmeladnyi) türkçe çeviri", "sucuk", 204),
            c("_-Nf59hG1N4", 0.7, "Мой мармеладный", "Катя Лель", 224),
            c("CuttNYrYIh8", 0.7, "Мой мармеладный (Я не права)", "surround. & (((())))", 225),
            c("befkc3LHXuQ", 0.76, "【日本語訳】Мой мармеладный (My Marmalade) speed up - Катя Лель (Katya Lel)", "やせるいつか", 188),
            c("hK6tF-hdlyM", 0.76, "Katya Lel — My Marmalade | Катя Лель — Мой мармеладный | sub español", "S i c k l y -", 192),
            c("hugnno0wF-k", 0.71, "Катя Лель (Katya Lel) - Мой мармеладный (Я не права) (My Marmalade (I messed up) - Lyrics", "Bubbles A330neo Lover", 224),
            c("ILTfUaztC8Y", 0.64, "Катя Лель - Мой Мармеладный", "Music World", 226),
            c("Xpp6kRLKcxw", 0.64, "Катя Лель - Мой мармеладный (КАРАОКЕ оригинальная аранжировка)", "ОКкараОК", 226),
            c("Lhz2e40fL98", 0.58, "(Try muah-muah, try jaga-jaga) ‏мой мармеладный- Katya Lel مترجمة للعربي (Lyrics) sub English", "LILI MUSIC", 203),
        ),
        emptySet(),
    )

    private val case6 = Case("Smooth Criminal - 2012 Remaster", "Michael Jackson", 257,
        listOf(
            c("XzNWRmqibNE", 1.05, "Smooth Criminal", "Michael Jackson", 258),
            c("_mMyPJSx8RU", 0.8, "Smooth Criminal (Radio Edit)", "Michael Jackson", 258),
            c("3s85TryYWgo", 0.56, "Smooth Criminal", "Toshihiko Matsunuma, Tokyo Kosei Wind Orchestra, Yasuto Tanaka & Soul Toru", 236),
            c("l33BVekuA3E", 0.55, "Smooth Criminal (feat. Jean Rodriguez)", "Tony Succar", 281),
        ),
        listOf(
            c("XzNWRmqibNE", 1.05, "Smooth Criminal", "Michael Jackson", 258),
            c("_mMyPJSx8RU", 0.8, "Smooth Criminal (Radio Edit)", "Michael Jackson", 258),
            c("3s85TryYWgo", 0.56, "Smooth Criminal", "Toshihiko Matsunuma, Tokyo Kosei Wind Orchestra, Yasuto Tanaka & Soul Toru", 236),
            c("l33BVekuA3E", 0.55, "Smooth Criminal (feat. Jean Rodriguez)", "Tony Succar", 281),
        ),
        emptySet(),
    )

    private val case7 = Case("Sunlight Sonata", "Sergei Novikov", 358,
        listOf(
            c("pcCgICC7Vfk", 0.43, "Moonlight Sonata", "Marcin & Ludwig van Beethoven", 331),
            c("fAg93OIuhZY", 0.26, "Moonlight Sonata - Sergei Polunin, Otobutai Festival, Japan", "Sergei Polunin", 481),
            c("GX68ewmvNr0", 0.26, "Sunlight Sonata: I. Prelude", "Nathan Lewis", 272),
            c("8l5jP5ZRaO0", 0.26, "Sunlight Sonata: III. Homage to Henry Mancini", "Nathan Lewis", 200),
        ),
        listOf(
            c("pcCgICC7Vfk", 0.43, "Moonlight Sonata", "Marcin & Ludwig van Beethoven", 331),
            c("fAg93OIuhZY", 0.26, "Moonlight Sonata - Sergei Polunin, Otobutai Festival, Japan", "Sergei Polunin", 481),
            c("GX68ewmvNr0", 0.26, "Sunlight Sonata: I. Prelude", "Nathan Lewis", 272),
            c("8l5jP5ZRaO0", 0.26, "Sunlight Sonata: III. Homage to Henry Mancini", "Nathan Lewis", 200),
            c("ey4dXrdow3M", 0.21, "\"Sunlight Sonata\" Made by Tony Ann plus the ending made by Zabdiel Torres Gallegos (blue notes) xD", "Zabdiel Torres Gallegos", 136),
        ),
        emptySet(),
    )

    private val case9 = Case("Raindrops Keep Fallin' on My Head - Rerecorded", "B.J. Thomas", 178,
        listOf(
            c("eKm2asQL1ME", 1.05, "Raindrops Keep Fallin' on My Head (Rerecorded)", "B.J. Thomas", 180),
            c("4bnKqnbCtao", 1.05, "Raindrops Keep Fallin' on My Head (Re-Recorded / Remastered)", "B.J. Thomas", 178),
            c("I3WMT90kako", 1.05, "Raindrops Keep Fallin' On My Head (Re-Recorded / Remastered)", "B.J. Thomas", 178),
            c("wWPU2mZXeWM", 1.05, "Raindrops Keep Fallin' On My Head", "B.J. Thomas", 176),
        ),
        listOf(
            c("eKm2asQL1ME", 1.05, "Raindrops Keep Fallin' on My Head (Rerecorded)", "B.J. Thomas", 180),
            c("4bnKqnbCtao", 1.05, "Raindrops Keep Fallin' on My Head (Re-Recorded / Remastered)", "B.J. Thomas", 178),
            c("I3WMT90kako", 1.05, "Raindrops Keep Fallin' On My Head (Re-Recorded / Remastered)", "B.J. Thomas", 178),
            c("wWPU2mZXeWM", 1.05, "Raindrops Keep Fallin' On My Head", "B.J. Thomas", 176),
        ),
        emptySet(),
    )

    private val case10 = Case("Peer Gynt Suite No. 1, Op. 46: Edvard GRIEG : Peer Gynt. Dans le hall du roi de la montagne - Recorded 1982", "Edvard Grieg, Berliner Philharmoniker, Herbert von Karajan", 129,
        listOf(
            c("WCi9l5FnK5Y", 0.8, "Peer Gynt Suite No. 1, Op. 46: In the Hall of the Mountain King", "Alexander Gibson, The London Festival Orchestra & Edvard Grieg", 134),
            c("D4ZoQHKEdIo", 0.76, "Peer Gynt Suite No. 1, Op. 46: In The Hall Of The Mountain King", "Arthur Fiedler & The Boston Pops Orchestra & Edvard Grieg", 139),
            c("DcuoWiUDf5o", 0.63, "Peer Gynt Suite No. 1, Op. 46: IV. In the Hall of the Mountain King", "Philadelphia Orchestra & Edvard Grieg", 169),
            c("HWQqixtYcu4", 0.63, "Peer Gynt Suite No. 1, Op. 46: IV. In the Hall of the Mountain King", "Philadelphia Orchestra & Edvard Grieg", 169),
        ),
        listOf(
            c("WCi9l5FnK5Y", 0.8, "Peer Gynt Suite No. 1, Op. 46: In the Hall of the Mountain King", "Alexander Gibson, The London Festival Orchestra & Edvard Grieg", 134),
            c("D4ZoQHKEdIo", 0.76, "Peer Gynt Suite No. 1, Op. 46: In The Hall Of The Mountain King", "Arthur Fiedler & The Boston Pops Orchestra & Edvard Grieg", 139),
            c("DcuoWiUDf5o", 0.63, "Peer Gynt Suite No. 1, Op. 46: IV. In the Hall of the Mountain King", "Philadelphia Orchestra & Edvard Grieg", 169),
            c("HWQqixtYcu4", 0.63, "Peer Gynt Suite No. 1, Op. 46: IV. In the Hall of the Mountain King", "Philadelphia Orchestra & Edvard Grieg", 169),
            c("3XQLJLsCKew", 0.82, "Peer Gynt, Suite No. 1, Op. 46: Dans le château du roi de la montagne", "Wiener Philharmoniker, Herbert von Karajan & Edvard Grieg", 150),
            c("hk0LlScBB8U", 0.05, "Grieg: Peer Gynt Suite No. 1, Op. 46: IV. In the Hall of the Mountain King", "Berliner Philharmoniker, Herbert von Karajan & Edvard Grieg", 130),
            c("lK732F7fvCA", 0.05, "Grieg: Peer Gynt Suite No. 1, Op. 46: IV. In the Hall of the Mountain King", "Royal Concertgebouw Orchestra, Jean Fournet & Edvard Grieg", 140),
            c("FzFUUags_L8", 0.72, "Peer Gynt, Suite No. 1, Op. 46: IV. In the Hall of the Mountain King", "Wiener Philharmoniker, Herbert von Karajan & Edvard Grieg", 147),
            c("D8hvLMmKST0", 0.7, "Peer Gynt Suite No. 1, Op. 46: IV. In the Hall of the Mountain King", "Herbert von Karajan, Wiener Philharmoniker & Edvard Grieg", 150),
            c("pZuiVcFm_-0", 0.45, "Peer Gynt Suite No. 1, Op. 46: Peet Gynt Suite No. 1, Op. 46: I. Morning", "BBC Scottish Symphony Orchestra & Edvard Grieg", 238),
            c("B9DNMSlwDeg", 0.67, "Dans l'Antre du Roi de la Montagne   Peer Gynt   Edvard Grieg Opus 46, No  4", "Vickery Bros Auto Piano", 150),
            c("S_sbI3_Bc5M", 0.6, "Edvard Grieg Peer Gynt Suite no. 1, Op. 46 - IV. In the Hall Of The Mountain King", "music_channel", 157),
            c("Be3yvl13mv4", 0.59, "Dans l'Antre du Roi de la Montagne - Peer Gynt - Edvard Grieg", "Mesdour - Musique au Collège", 180),
        ),
        emptySet(),
    )

    private val case11 = Case("Put Your Head On My Shoulder", "Paul Anka", 154,
        listOf(
            c("l_OPM280w0Y", 1.05, "Put Your Head on My Shoulder", "Paul Anka", 155),
            c("UawIdB6xXp4", 1.05, "Put Your Head On My Shoulder", "Paul Anka", 157),
            c("mHLAYYqA1DE", 1.05, "Put Your Head On My Shoulder", "Paul Anka", 157),
            c("aNqX_uPwvrA", 1.05, "Put Your Head On My Shoulder", "Paul Anka & Don Costa And His Orchestra", 157),
        ),
        listOf(
            c("l_OPM280w0Y", 1.05, "Put Your Head on My Shoulder", "Paul Anka", 155),
            c("UawIdB6xXp4", 1.05, "Put Your Head On My Shoulder", "Paul Anka", 157),
            c("mHLAYYqA1DE", 1.05, "Put Your Head On My Shoulder", "Paul Anka", 157),
            c("aNqX_uPwvrA", 1.05, "Put Your Head On My Shoulder", "Paul Anka & Don Costa And His Orchestra", 157),
            c("tOwrbcmnwu0", 1.0, "Paul Anka - Put Your Head On My Shoulder (Español)", "Wild Youth Subs", 157),
            c("n18IaURNw48", 0.97, "Put Your Head on My Shoulder - Paul Anka || SoulBlues Version", "SoulSmith", 148),
            c("xmyjlSbv_4Y", 0.96, "Paul Anka - Put Your Head On My Shoulder (Traduction Française)", "LyriTrad", 162),
        ),
        emptySet(),
    )

    private val case12 = Case("Sources", "Arlen Grace", 138,
        listOf(
            c("9fJZmPSq0cE", 0.05, "forsaken", "Arlen Grace & kolya44", 83),
            c("xFfy7zkDF7Y", 0.05, "office", "Arlen Grace, Gemroz & kolya44", 135),
            c("-7jNhP81TWo", 0.05, "FRÈ AK SÈ (Live)", "Grace Son", 305),
            c("tXoDFHbB3bI", 0.05, "Higher Grace", "Aranie Fleur De Cerisier", 231),
        ),
        listOf(
            c("9fJZmPSq0cE", 0.05, "forsaken", "Arlen Grace & kolya44", 83),
            c("xFfy7zkDF7Y", 0.05, "office", "Arlen Grace, Gemroz & kolya44", 135),
            c("-7jNhP81TWo", 0.05, "FRÈ AK SÈ (Live)", "Grace Son", 305),
            c("tXoDFHbB3bI", 0.05, "Higher Grace", "Aranie Fleur De Cerisier", 231),
        ),
        emptySet(),
    )

    private val case13 = Case("Gastronomic Concerto - Concerto pour l’Aile ou la cuisse", "Vladimir Cosma", 186,
        listOf(
            c("OGvmOh92kwM", 0.86, "Gastronomic Concerto (Concerto pour l’Aile ou la cuisse)", "Vladimir Cosma", 214),
            c("qPsfmY9YD18", 0.05, "L'Aile ou la Cuisse: Concerto Gastronomique (Live)", "Vladimir Cosma, Orchestre National Symphonique de Roumanie, Guillaume Connesson & Cezar Cazanoi", 415),
            c("fjNqaRNz-5k", 0.05, "Happy Meal (Concerto pour l’Aile ou la cuisse)", "Vladimir Cosma", 150),
            c("8FVciJRawFE", 0.05, "Concerto de Berlin (Pour violon et orchestre - Version intégrale)", "Vladimir Cosma, Orchestre National de Lyon & Vadim Repin", 1658),
        ),
        listOf(
            c("OGvmOh92kwM", 0.86, "Gastronomic Concerto (Concerto pour l’Aile ou la cuisse)", "Vladimir Cosma", 214),
            c("qPsfmY9YD18", 0.05, "L'Aile ou la Cuisse: Concerto Gastronomique (Live)", "Vladimir Cosma, Orchestre National Symphonique de Roumanie, Guillaume Connesson & Cezar Cazanoi", 415),
            c("fjNqaRNz-5k", 0.05, "Happy Meal (Concerto pour l’Aile ou la cuisse)", "Vladimir Cosma", 150),
            c("8FVciJRawFE", 0.05, "Concerto de Berlin (Pour violon et orchestre - Version intégrale)", "Vladimir Cosma, Orchestre National de Lyon & Vadim Repin", 1658),
            c("VeHsYuf93Iw", 0.05, "Le passage secret (From \"Vous n'aurez pas l'Alsace et la Lorraine\")", "Vladimir Cosma", 75),
            c("rSF-TXpQdT8", 0.05, "Na Democracy", "Vladimir Cosma", 253),
            c("-yXvj2lmrOE", 0.05, "Herminie mord le Comte", "Vladimir Cosma", 127),
            c("v9TbINbSmp8", 0.6, "Gastronomic Concerto (Concerto pour l’Aile ou la cuisse)", "Vladimir Cosma", 415),
        ),
        emptySet(),
    )

    private val case14 = Case("Pavane pour une infante défunte, M. 19a", "Maurice Ravel, Berliner Philharmoniker, Herbert von Karajan", 433,
        listOf(
            c("R2aqZ0bvx_0", 1.01, "Ravel: Pavane pour une infante défunte, M. 19a", "Berliner Philharmoniker, Herbert von Karajan & Maurice Ravel", 434),
            c("pSLSny7J9M8", 0.87, "Ravel: Pavane pour une infante défunte, M. 19", "Alice Sara Ott & Maurice Ravel", 417),
            c("rS0oXfVbQcA", 0.84, "Pavane pour une infante défunte, M. 19", "Fritz Reiner, Chicago Symphony Orchestra & Maurice Ravel", 389),
            c("wzMzqWNeovk", 0.84, "Pavane pour une infante défunte, M. 19", "Cleveland Orchestra, George Szell & Maurice Ravel", 391),
        ),
        listOf(
            c("R2aqZ0bvx_0", 1.01, "Ravel: Pavane pour une infante défunte, M. 19a", "Berliner Philharmoniker, Herbert von Karajan & Maurice Ravel", 434),
            c("pSLSny7J9M8", 0.87, "Ravel: Pavane pour une infante défunte, M. 19", "Alice Sara Ott & Maurice Ravel", 417),
            c("rS0oXfVbQcA", 0.84, "Pavane pour une infante défunte, M. 19", "Fritz Reiner, Chicago Symphony Orchestra & Maurice Ravel", 389),
            c("wzMzqWNeovk", 0.84, "Pavane pour une infante défunte, M. 19", "Cleveland Orchestra, George Szell & Maurice Ravel", 391),
        ),
        emptySet(),
    )

    private val case15 = Case("Looney Tunes Theme", "Carl Stalling, London Music Works, Evan Jolly", 38,
        listOf(
            c("0lDX_CYQcBA", 0.89, "Looney Tunes Opening Theme (Carl Stalling)", "Dominik Hauser", 30),
            c("3ZmT-57zvlQ", 0.43, "Looney Tunes - Opening Theme", "Geek Music", 63),
            c("lcwo6evcNm4", 0.43, "Looney Tunes", "Musosis", 20),
            c("bsMkCMfJhZU", 0.05, "Merrie Melodies (Dalla serie dei cartoni animati \"Looney Tunes\")", "Double Zero Orchestra", 30),
        ),
        listOf(
            c("0lDX_CYQcBA", 0.89, "Looney Tunes Opening Theme (Carl Stalling)", "Dominik Hauser", 30),
            c("3ZmT-57zvlQ", 0.43, "Looney Tunes - Opening Theme", "Geek Music", 63),
            c("lcwo6evcNm4", 0.43, "Looney Tunes", "Musosis", 20),
            c("bsMkCMfJhZU", 0.05, "Merrie Melodies (Dalla serie dei cartoni animati \"Looney Tunes\")", "Double Zero Orchestra", 30),
            c("DHpOls0_Li0", 0.61, "Looney Tunes Opening (What’s Up Doc?) / Rabbit Fire", "Carl Stalling & Jerry Goldsmith", 69),
            c("JnaPYX9tFTY", 0.5, "Space Jam - Looney Tunes Theme", "Royal Symphony Orchestra", 74),
            c("zQm4U4blQ1s", 0.44, "Looney Tunes Opening Theme (From \"Looney Tunes\")", "Just Kids", 16),
            c("iEoHmjDJiI8", 0.91, "5 - The looney tunes theme. Carl Stalling.", "João Bastos Guitarra", 30),
            c("p_Izv6sq4dQ", 0.65, "LOONEY TUNES - Main Theme | TRAP VERSION By Carl Stalling | Warner Bros.", "Trap Geek", 86),
            c("_tSpQYM_1Lg", 0.55, "Sopranos Music Video Looney Tunes Theme", "Stephen Thompson", 29),
        ),
        emptySet(),
    )

    private val case16 = Case("Romeo and Juliet, Fantasy Overture, TH 42", "Pyotr Ilyich Tchaikovsky, Berliner Philharmoniker, Claudio Abbado", 1213,
        listOf(
            c("znKS1QuCUI0", 1.01, "Tchaikovsky: Romeo and Juliet, Fantasy Overture, TH 42", "Berliner Philharmoniker, Claudio Abbado & Pyotr Ilyich Tchaikovsky", 1213),
            c("F1qNHSlS64I", 1.01, "Tchaikovsky: Romeo and Juliet, Fantasy Overture, TH 42", "Chicago Symphony Orchestra, Sir Georg Solti & Pyotr Ilyich Tchaikovsky", 1215),
            c("AVVJtpHOfd4", 0.88, "Tchaikovsky: Romeo and Juliet, Fantasy Overture, TH 42", "Chicago Symphony Orchestra, Daniel Barenboim & Pyotr Ilyich Tchaikovsky", 1233),
            c("EnsoyOgrcls", 0.85, "Romeo and Juliet Fantasy Overture, TH 42", "Leonard Bernstein, New York Philharmonic Orchestra & Pyotr Ilyich Tchaikovsky", 1164),
        ),
        listOf(
            c("znKS1QuCUI0", 1.01, "Tchaikovsky: Romeo and Juliet, Fantasy Overture, TH 42", "Berliner Philharmoniker, Claudio Abbado & Pyotr Ilyich Tchaikovsky", 1213),
            c("F1qNHSlS64I", 1.01, "Tchaikovsky: Romeo and Juliet, Fantasy Overture, TH 42", "Chicago Symphony Orchestra, Sir Georg Solti & Pyotr Ilyich Tchaikovsky", 1215),
            c("AVVJtpHOfd4", 0.88, "Tchaikovsky: Romeo and Juliet, Fantasy Overture, TH 42", "Chicago Symphony Orchestra, Daniel Barenboim & Pyotr Ilyich Tchaikovsky", 1233),
            c("EnsoyOgrcls", 0.85, "Romeo and Juliet Fantasy Overture, TH 42", "Leonard Bernstein, New York Philharmonic Orchestra & Pyotr Ilyich Tchaikovsky", 1164),
            c("xYCpAte43kk", 0.98, "Tchaikovsky: Romeo and Juliet, Fantasy Overture, TH 42", "Berliner Philharmoniker, Claudio Abbado & Pyotr Ilyich Tchaikovsky", 1208),
            c("Zjc9PVjlnMA", 0.96, "Tchaikovsky: Romeo and Juliet, Fantasy Overture, TH 42", "Boston Symphony Orchestra, Claudio Abbado & Pyotr Ilyich Tchaikovsky", 1222),
            c("l5nZvS63q1Y", 0.93, "Romeo and Juliet Fantasy Overture, TH 42", "Claudio Abbado, Chicago Symphony Orchestra & Pyotr Ilyich Tchaikovsky", 1232),
            c("scbjlBoE4Cc", 0.47, "Pyotr Tchaikovsky - Romeo and Juliet Overture Fantasia  TH 42, ČW 39", "The Classics", 1213),
            c("Av-xed7Ljrg", 0.43, "♫ Pyotr Ilyich Tchaikovsky - Romeo and Juliet Overture Fantasia ♫", "The Best Classical Playlist", 1112),
            c("finYYXkdYmA", 0.36, "Tchaikovsky: Romeo and Juliet (Final version), TH 42 (with Score)", "symphony7526", 1232),
        ),
        emptySet(),
    )

    private fun all() = listOf(case0, case1, case2, case3, case4, case5, case6, case7, case9, case10, case11, case12, case13, case14, case15, case16)

    @Test fun tracksMissingFromYouTubeMusicAreNotReplacedByAnotherSong() {
        assertEquals("What falling in love feels like", null, case0.choice())
        assertEquals("sex seven", null, case1.choice())
        assertEquals("?", null, case3.choice())
        assertEquals("MY MAN IS ABOUT TO HIT A SPECIAL ONE", null, case4.choice())
        assertEquals("Sunlight Sonata", null, case7.choice())
        assertEquals("Sources", null, case12.choice())
    }

    @Test fun theRecordingOfTheSameSongIsKept() {
        assertEquals("Hung Up", "20OGm_-RebE", case2.choice())
        assertEquals("Мой мармеладный (Я не права)", "_-Nf59hG1N4", case5.choice())
        assertEquals("Smooth Criminal - 2012 Remaster", "XzNWRmqibNE", case6.choice())
        assertEquals("Looney Tunes Theme", "0lDX_CYQcBA", case15.choice())
    }

    /** Classical music: the performance credited to the same orchestra and conductor, whatever the movement is called. */
    @Test fun classicalPerformanceIsChosenByItsCredits() {
        val peerGynt = case10.all.first { it.first.id.endsWith(case10.choice()!!) }.first
        assertTrue(peerGynt.subtitle, peerGynt.subtitle.contains("Berliner Philharmoniker") && peerGynt.subtitle.contains("Karajan"))
        val romeo = case16.all.first { it.first.id.endsWith(case16.choice()!!) }.first
        assertTrue(romeo.subtitle, romeo.subtitle.contains("Berliner Philharmoniker") && romeo.subtitle.contains("Abbado"))
    }

    /** Every other track plays a recording with its title and artist. */
    @Test fun everyChoiceHasTheTitleAndArtist() {
        for (case in all()) {
            val id = case.choice() ?: continue
            val chosen = case.all.first { it.first.id.endsWith(id) }.first
            assertTrue("${case.title} → ${chosen.title} / ${chosen.subtitle}", TrackMatcher.plausible(case.query, chosen) || TrackMatcher.sameCredits(case.query, chosen))
        }
    }
}

