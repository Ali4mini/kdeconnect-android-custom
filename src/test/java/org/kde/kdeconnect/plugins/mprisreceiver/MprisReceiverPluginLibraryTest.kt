package org.kde.kdeconnect.plugins.mprisreceiver

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MprisReceiverPluginLibraryTest {
    private lateinit var plugin: MprisReceiverPlugin
    private lateinit var context: Context
    private lateinit var device: Device
    private var sentLatch: CountDownLatch = CountDownLatch(1)
    private var lastPacket: NetworkPacket? = null

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sentLatch = CountDownLatch(1)
        lastPacket = null
        device = mockk {
            every { sendPacket(any()) } answers {
                lastPacket = arg(0)
                sentLatch.countDown()
            }
        }
        val fakeSession = object : MediaBrowserLibraryHelper.ConnectedSession {
            override val rootId: String = "root.0"
            override val searchSupported: Boolean = false
            override fun subscribeOnce(parentId: String): List<MediaBrowserLibraryHelper.LibraryItem> =
                if (parentId == "root.0") {
                    listOf(MediaBrowserLibraryHelper.LibraryItem("albums", "Albums", "", true, false))
                } else {
                    emptyList()
                }

            override fun playFromMediaId(mediaId: String): Boolean = mediaId == "track.1"
            override fun disconnect() = Unit
        }
        val backend = object : MediaBrowserLibraryHelper.BrowserBackend {
            override fun ownPackageName(): String = "org.kde.kdeconnect_tp.debug"
            override fun discoverServices(): List<MediaBrowserLibraryHelper.DiscoveredService> = listOf(
                MediaBrowserLibraryHelper.DiscoveredService("Musicolet", "in.krosbits.musicolet", "Svc", true)
            )
            override fun connect(
                packageName: String,
                serviceName: String,
                onDisconnected: () -> Unit,
            ): MediaBrowserLibraryHelper.ConnectedSession = fakeSession
        }
        plugin = MprisReceiverPlugin().also {
            it.setContext(context, device)
            it.setLibraryHelperForTest(MediaBrowserLibraryHelper(backend))
        }
    }

    @Test
    fun onPacketReceived_handlesLibraryPlayersBrowseAndPlay() {
        val playersReq = NetworkPacket("kdeconnect.mpris.request").apply {
            set("libraryRequestId", "req-players")
            set("libraryAction", "players")
        }
        assertTrue(plugin.onPacketReceived(playersReq))
        assertTrue(sentLatch.await(3, TimeUnit.SECONDS))
        val playersResp = lastPacket
        assertNotNull(playersResp)
        assertEquals("req-players", playersResp!!.getString("libraryRequestId"))
        val playersJson = Json.parseToJsonElement(playersResp.getString("libraryReply")).jsonObject
        assertEquals("Musicolet", playersJson["players"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)

        sentLatch = CountDownLatch(1)
        val browseReq = NetworkPacket("kdeconnect.mpris.request").apply {
            set("libraryRequestId", "req-browse")
            set("libraryAction", "browse")
            set("player", "Musicolet")
        }
        assertTrue(plugin.onPacketReceived(browseReq))
        assertTrue(sentLatch.await(3, TimeUnit.SECONDS))
        val browseJson = Json.parseToJsonElement(lastPacket!!.getString("libraryReply")).jsonObject
        assertTrue(browseJson["available"]!!.jsonPrimitive.boolean)
        assertEquals("albums", browseJson["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)

        sentLatch = CountDownLatch(1)
        val playReq = NetworkPacket("kdeconnect.mpris.request").apply {
            set("libraryRequestId", "req-play")
            set("libraryAction", "play")
            set("player", "Musicolet")
            set("mediaId", "track.1")
        }
        assertTrue(plugin.onPacketReceived(playReq))
        assertTrue(sentLatch.await(3, TimeUnit.SECONDS))
        val playJson = Json.parseToJsonElement(lastPacket!!.getString("libraryReply")).jsonObject
        assertTrue(playJson["accepted"]!!.jsonPrimitive.boolean)

        sentLatch = CountDownLatch(1)
        val badReq = NetworkPacket("kdeconnect.mpris.request").apply {
            set("libraryRequestId", "req-bad")
            set("libraryAction", "unknown")
        }
        assertTrue(plugin.onPacketReceived(badReq))
        assertTrue(sentLatch.await(3, TimeUnit.SECONDS))
        val badJson = Json.parseToJsonElement(lastPacket!!.getString("libraryReply")).jsonObject
        assertFalse(badJson["available"]!!.jsonPrimitive.boolean)
    }
}
