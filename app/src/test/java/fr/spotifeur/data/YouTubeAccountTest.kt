package fr.spotifeur.data

import fr.spotifeur.data.youtube.SignInStep
import fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX
import fr.spotifeur.data.youtube.YouTubeCredential
import fr.spotifeur.data.youtube.cookieValue
import fr.spotifeur.data.youtube.delegatedPageId
import fr.spotifeur.data.youtube.isEditablePlaylist
import fr.spotifeur.data.youtube.isYouTubeHome
import fr.spotifeur.data.youtube.isYouTubeMusic
import fr.spotifeur.data.youtube.mergeCookies
import fr.spotifeur.data.youtube.parseAccountMenu
import fr.spotifeur.data.youtube.parseLibraryPlaylists
import fr.spotifeur.data.youtube.parsePlaylistRows
import fr.spotifeur.data.youtube.playlistPrivacy
import fr.spotifeur.data.youtube.sapisidHash
import fr.spotifeur.data.youtube.signInStep
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Signed-in YouTube Music requests: header values and response shapes, checked against hand-built doubles. */
class YouTubeAccountTest {

    // Session ------------------------------------------------------------------------------------

    @Test fun sapisidHashIsSha1OfSecondsKeyAndOrigin() {
        val expected = MessageDigest.getInstance("SHA-1").digest("1700000000 abc/DEF https://music.youtube.com".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("SAPISIDHASH 1700000000_$expected", sapisidHash("abc/DEF", "https://music.youtube.com", 1_700_000_000))
        assertEquals(40, expected.length)
    }

    @Test fun cookieValuesAreReadByExactName() {
        val cookie = "__Secure-3PAPISID=three; SAPISID=main; APISID=other"
        assertEquals("main", cookieValue(cookie, "SAPISID"))
        assertEquals("three", cookieValue(cookie, "__Secure-3PAPISID"))
        assertNull(cookieValue(cookie, "SID"))
    }

    @Test fun signInPagesAreRecognizedByExactHost() {
        assertTrue(isYouTubeMusic("https://music.youtube.com/"))
        assertTrue(isYouTubeMusic("https://music.youtube.com/library?x=1"))
        assertFalse(isYouTubeMusic("https://music.youtube.com.example.org/"))
        assertFalse(isYouTubeMusic("http://music.youtube.com/"))
        assertTrue(isYouTubeHome("https://www.youtube.com/"))
        assertTrue(isYouTubeHome("https://m.youtube.com/?persist_app=1"))
        assertTrue(isYouTubeHome("https://YouTube.com"))
        // The sign-in handler, the consent page and other YouTube pages are left alone.
        assertFalse(isYouTubeHome("https://www.youtube.com/signin?action_handle_signin=true"))
        assertFalse(isYouTubeHome("https://consent.youtube.com/ml?continue=x"))
        assertFalse(isYouTubeHome("https://www.youtube.com/watch?v=x"))
        assertFalse(isYouTubeHome("https://www.youtube.com.example.org/"))
        assertFalse(isYouTubeHome("not a url"))
        assertFalse(isYouTubeHome(null))
    }

    @Test fun signInEndsOnYouTubeMusicOrReportsTheDeadEnd() {
        val music = "https://music.youtube.com/"
        val home = "https://m.youtube.com/"
        assertEquals(SignInStep.Finish, signInStep(music, signedIn = true, musicRedirects = 0))
        assertEquals(SignInStep.Finish, signInStep(music, signedIn = true, musicRedirects = 2))
        assertEquals(SignInStep.Wait, signInStep(music, signedIn = false, musicRedirects = 0))
        // Google's pages and the sign-in handler come before the end.
        assertEquals(SignInStep.Wait, signInStep("https://accounts.google.com/ServiceLogin", signedIn = false, musicRedirects = 0))
        assertEquals(SignInStep.Wait, signInStep("https://www.youtube.com/signin?action_handle_signin=true", signedIn = true, musicRedirects = 0))
        assertEquals(SignInStep.OpenMusic, signInStep(home, signedIn = true, musicRedirects = 0))
        assertEquals(SignInStep.OpenMusic, signInStep(home, signedIn = true, musicRedirects = 1))
        // No loop, and no silent stop on the home page.
        assertEquals(SignInStep.Fail, signInStep(home, signedIn = true, musicRedirects = 2))
        assertEquals(SignInStep.Fail, signInStep(home, signedIn = false, musicRedirects = 0))
    }

    @Test fun eitherSessionCookieSignsIn() {
        assertEquals("a", YouTubeCredential("SID=x; SAPISID=a").sapisid)
        assertEquals("b", YouTubeCredential("SID=x; __Secure-3PAPISID=b").sapisid)
        assertNull(YouTubeCredential("").sapisid)
        assertNull(YouTubeCredential("VISITOR_INFO1_LIVE=v; YSC=y").sapisid)
    }

    @Test fun setCookieReplacesAndExpires() {
        val merged = mergeCookies("SID=a; SAPISID=b; PSIDTS=old", listOf(
            "PSIDTS=new; Path=/; Domain=.youtube.com; Secure; HttpOnly",
            "SID=; Max-Age=0; Path=/",
            "VISITOR=v; Expires=Wed, 01 Jan 2031 00:00:00 GMT",
        ))
        assertEquals("SAPISID=b; PSIDTS=new; VISITOR=v", merged)
    }

    @Test fun onlyBrandAccountsAreDelegated() {
        assertNull(delegatedPageId("1234567890||"))
        assertNull(delegatedPageId(null))
        assertNull(delegatedPageId("||1234"))
        assertEquals("PAGE", delegatedPageId("PAGE||1234"))
    }

    // Responses ----------------------------------------------------------------------------------

    private fun runs(vararg texts: String) = JSONObject().put("runs", JSONArray(texts.map { JSONObject().put("text", it) }))

    @Test fun accountMenuGivesNameHandleAndPhoto() {
        val header = JSONObject()
            .put("accountName", runs("Jean"))
            .put("channelHandle", runs("@jean"))
            .put("accountPhoto", JSONObject().put("thumbnails", JSONArray()
                .put(JSONObject().put("url", "https://yt3.ggpht.com/small"))
                .put(JSONObject().put("url", "https://yt3.ggpht.com/large"))))
        val response = JSONObject().put("actions", JSONArray().put(JSONObject().put("openPopupAction", JSONObject()
            .put("popup", JSONObject().put("multiPageMenuRenderer", JSONObject()
                .put("header", JSONObject().put("activeAccountHeaderRenderer", header)))))))
        val info = parseAccountMenu(response)!!
        assertEquals("Jean", info.name)
        assertEquals("@jean", info.handle)
        assertEquals("https://yt3.ggpht.com/large", info.photoUrl)
        assertNull(parseAccountMenu(JSONObject().put("responseContext", JSONObject())))
    }

    private fun card(title: String, browseId: String?, vararg subtitle: String) = JSONObject().put("musicTwoRowItemRenderer", JSONObject()
        .put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", title).apply {
            if (browseId != null) put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", browseId)))
        })))
        .put("subtitle", runs(*subtitle))
        .put("thumbnailRenderer", JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail", JSONObject()
            .put("thumbnails", JSONArray().put(JSONObject().put("url", "https://i.ytimg.com/$title.jpg")))))))

    @Test fun libraryGridKeepsPlaylistsOnly() {
        val grid = JSONObject().put("gridRenderer", JSONObject().put("items", JSONArray()
            .put(card("New playlist", null))
            .put(card("Liked Music", "VLLM", "Auto playlist"))
            .put(card("Road trip", "VLPLabc123", "Jean", " • ", "12 songs"))
            .put(card("Podcasts", "VLSE", "Episodes"))))
        val playlists = parseLibraryPlaylists(JSONObject().put("contents", grid))
        assertEquals(listOf("${YTM_PLAYLIST_PREFIX}LM", "${YTM_PLAYLIST_PREFIX}PLabc123"), playlists.map(Music::id))
        assertEquals(MusicKind.Playlist, playlists[1].kind)
        assertEquals("Jean", playlists[1].ownerUsername)
        assertNull(playlists[0].ownerUsername)
        assertEquals("Jean • 12 songs", playlists[1].subtitle)
    }

    private fun row(videoId: String, title: String, artist: String, setVideoId: String?) = JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
        .put("flexColumns", JSONArray()
            .put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", JSONArray()
                .put(JSONObject().put("text", title).put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", videoId))))))))
            .put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", runs(artist)))))
        .put("fixedColumns", JSONArray().put(JSONObject().put("musicResponsiveListItemFixedColumnRenderer", JSONObject().put("text", runs("3:05")))))
        .apply { if (setVideoId != null) put("playlistItemData", JSONObject().put("videoId", videoId).put("playlistSetVideoId", setVideoId)) })

    @Test fun ownerPageGivesRowIdsEditingAndPrivacy() {
        val shelf = JSONObject().put("musicPlaylistShelfRenderer", JSONObject().put("contents", JSONArray()
            .put(row("aaaaaaaaaaa", "First", "Artist", "SET1"))
            .put(row("aaaaaaaaaaa", "First", "Artist", "SET2"))
            .put(row("bbbbbbbbbbb", "Second", "Other", null))))
        val header = JSONObject().put("musicEditablePlaylistDetailHeaderRenderer", JSONObject()
            .put("editHeader", JSONObject().put("musicPlaylistEditHeaderRenderer", JSONObject().put("privacy", "PRIVATE"))))
        val page = JSONObject().put("header", header).put("contents", shelf)
        val rows = parsePlaylistRows(page)
        assertEquals(listOf("SET1", "SET2", null), rows.map { it.second })
        assertEquals("ytm:track:aaaaaaaaaaa", rows[0].first.id)
        assertEquals(185_000, rows[0].first.durationMs)
        assertTrue(isEditablePlaylist(page))
        assertEquals("PRIVATE", playlistPrivacy(page))
        assertFalse(isEditablePlaylist(JSONObject().put("contents", shelf)))
        assertNull(playlistPrivacy(JSONObject().put("contents", shelf)))
    }
}
