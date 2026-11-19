package org.kde.kdeconnect.plugins.mprisreceiver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBrowserLibraryHelperTest {

    private class FakeSession(
        override val rootId: String,
        override val searchSupported: Boolean = false,
        private val tree: Map<String, List<MediaBrowserLibraryHelper.LibraryItem>> = emptyMap(),
        private val playableResult: (String) -> Boolean = { true },
    ) : MediaBrowserLibraryHelper.ConnectedSession {
        val playedIds = mutableListOf<String>()
        var disconnected = false

        override fun subscribeOnce(parentId: String): List<MediaBrowserLibraryHelper.LibraryItem>? = tree[parentId]

        override fun playFromMediaId(mediaId: String): Boolean {
            playedIds += mediaId
            return playableResult(mediaId)
        }

        override fun disconnect() {
            disconnected = true
        }
    }

    private class FakeBackend(
        private val selfPackage: String = "org.kde.kdeconnect_tp.debug",
        var services: List<MediaBrowserLibraryHelper.DiscoveredService> = emptyList(),
        private val connector: (String, String, () -> Unit) -> MediaBrowserLibraryHelper.ConnectedSession? = { _, _, _ -> null },
    ) : MediaBrowserLibraryHelper.BrowserBackend {
        var connectCount = 0

        override fun ownPackageName(): String = selfPackage

        override fun discoverServices(): List<MediaBrowserLibraryHelper.DiscoveredService> = services

        override fun connect(
            packageName: String,
            serviceName: String,
            onDisconnected: () -> Unit,
        ): MediaBrowserLibraryHelper.ConnectedSession? {
            connectCount++
            return connector(packageName, serviceName, onDisconnected)
        }
    }

    @Test
    fun readLibraryPlayers_filtersExcludesSortsActiveFirstAndDedupes() {
        val backend = FakeBackend(
            services = listOf(
                MediaBrowserLibraryHelper.DiscoveredService("KDE Connect", "org.kde.kdeconnect_tp.debug", "S", true),
                MediaBrowserLibraryHelper.DiscoveredService("Bluetooth", "com.android.bluetooth", "Bt", true),
                MediaBrowserLibraryHelper.DiscoveredService("Bixby", "com.samsung.bixby", "B", false),
                MediaBrowserLibraryHelper.DiscoveredService("VLC", "org.videolan.vlc", "PlaybackService", true),
                MediaBrowserLibraryHelper.DiscoveredService("Musicolet", "in.krosbits.musicolet", "MediaBrowserServiceImpl", true),
                MediaBrowserLibraryHelper.DiscoveredService("Musicolet", "in.krosbits.musicolet", "DupService", true),
            )
        )
        val helper = MediaBrowserLibraryHelper(backend)
        val active = mapOf("in.krosbits.musicolet" to MediaBrowserLibraryHelper.ActiveSessionInfo("Musicolet", canQueue = true))

        val reply = Json.parseToJsonElement(helper.readLibraryPlayers(active)).jsonObject
        val players = reply["players"]!!.jsonArray
        assertEquals(2, players.size)

        val first = players[0].jsonObject
        assertEquals("Musicolet", first["name"]!!.jsonPrimitive.content)
        assertEquals("in.krosbits.musicolet", first["packageName"]!!.jsonPrimitive.content)
        assertTrue(first["active"]!!.jsonPrimitive.boolean)
        assertTrue(first["canQueue"]!!.jsonPrimitive.boolean)
        assertTrue(first["canBrowse"]!!.jsonPrimitive.boolean)

        val second = players[1].jsonObject
        assertEquals("VLC", second["name"]!!.jsonPrimitive.content)
        assertFalse(second["active"]!!.jsonPrimitive.boolean)
        assertFalse(second["canQueue"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun browse_rootAndChildReusesCachedConnectionAndTruncatesOver1000() {
        val manySongs = (0 until 1050).map { idx ->
            MediaBrowserLibraryHelper.LibraryItem("song_$idx", "Track $idx", "Artist $idx", browsable = false, playable = true)
        }
        val session = FakeSession(
            rootId = "musicolet.media.r.0",
            searchSupported = false,
            tree = mapOf(
                "musicolet.media.r.0" to listOf(
                    MediaBrowserLibraryHelper.LibraryItem("musicolet.media.t.2", "Library", "", browsable = true, playable = false),
                ),
                "musicolet.media.r.3" to manySongs,
            )
        )
        val backend = FakeBackend(
            services = listOf(
                MediaBrowserLibraryHelper.DiscoveredService("Musicolet", "in.krosbits.musicolet", "MediaBrowserServiceImpl", true),
            ),
            connector = { _, _, _ -> session }
        )
        val helper = MediaBrowserLibraryHelper(backend)

        val rootReply = Json.parseToJsonElement(helper.browse("Musicolet", null)).jsonObject
        assertTrue(rootReply["available"]!!.jsonPrimitive.boolean)
        assertEquals("musicolet.media.r.0", rootReply["rootId"]!!.jsonPrimitive.content)
        assertEquals("musicolet.media.r.0", rootReply["parentId"]!!.jsonPrimitive.content)
        assertFalse(rootReply["searchSupported"]!!.jsonPrimitive.boolean)
        assertEquals(1, rootReply["items"]!!.jsonArray.size)

        val songsReply = Json.parseToJsonElement(helper.browse("Musicolet", "musicolet.media.r.3")).jsonObject
        assertTrue(songsReply["available"]!!.jsonPrimitive.boolean)
        assertTrue(songsReply["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(1050, songsReply["totalCount"]!!.jsonPrimitive.int)
        assertEquals(1000, songsReply["items"]!!.jsonArray.size)
        assertEquals(1, backend.connectCount)

        helper.release()
        assertTrue(session.disconnected)
    }

    @Test
    fun browse_reconnectsWhenCachedSessionSuspends() {
        var disconnectCb: (() -> Unit)? = null
        val session1 = FakeSession(rootId = "root1", tree = mapOf("root1" to emptyList()))
        val session2 = FakeSession(
            rootId = "root2",
            tree = mapOf("root2" to listOf(MediaBrowserLibraryHelper.LibraryItem("a", "Albums", "", true, false)))
        )
        var n = 0
        val backend = FakeBackend(
            services = listOf(MediaBrowserLibraryHelper.DiscoveredService("Musicolet", "in.krosbits.musicolet", "Svc", true)),
            connector = { _, _, onDisc ->
                disconnectCb = onDisc
                n++
                if (n == 1) session1 else session2
            }
        )
        val helper = MediaBrowserLibraryHelper(backend)

        val first = Json.parseToJsonElement(helper.browse("Musicolet", null)).jsonObject
        assertEquals("root1", first["rootId"]!!.jsonPrimitive.content)

        disconnectCb!!.invoke()
        val second = Json.parseToJsonElement(helper.browse("Musicolet", null)).jsonObject
        assertEquals("root2", second["rootId"]!!.jsonPrimitive.content)
        assertEquals(2, backend.connectCount)
    }

    @Test
    fun playMediaId_dispatchesPlayAndHandlesUnknownPlayer() {
        val session = FakeSession(rootId = "root")
        val backend = FakeBackend(
            services = listOf(MediaBrowserLibraryHelper.DiscoveredService("Musicolet", "in.krosbits.musicolet", "Svc", true)),
            connector = { _, _, _ -> session }
        )
        val helper = MediaBrowserLibraryHelper(backend)

        val ok = Json.parseToJsonElement(helper.playMediaId("Musicolet", "musicolet.media.r.12.s_0")).jsonObject
        assertTrue(ok["accepted"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("musicolet.media.r.12.s_0"), session.playedIds)

        val missing = Json.parseToJsonElement(helper.playMediaId("NoSuchPlayer", "x")).jsonObject
        assertFalse(missing["accepted"]!!.jsonPrimitive.boolean)
    }
}
