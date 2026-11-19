/*
 * SPDX-FileCopyrightText: 2026 Ali4mini Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mprisreceiver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.kde.kdeconnect.helpers.AppsHelper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class MediaBrowserLibraryHelper(
    private val backend: BrowserBackend,
) {
    constructor(context: Context) : this(AndroidMediaBrowserBackend(context.applicationContext))

    data class DiscoveredService(
        val name: String,
        val packageName: String,
        val serviceName: String,
        val exported: Boolean,
    )

    data class ActiveSessionInfo(
        val name: String,
        val canQueue: Boolean,
    )

    data class LibraryItem(
        val id: String,
        val title: String,
        val subtitle: String,
        val browsable: Boolean,
        val playable: Boolean,
    )

    interface ConnectedSession {
        val rootId: String
        val searchSupported: Boolean
        fun subscribeOnce(parentId: String): List<LibraryItem>?
        fun playFromMediaId(mediaId: String): Boolean
        fun disconnect()
    }

    interface BrowserBackend {
        fun ownPackageName(): String
        fun discoverServices(): List<DiscoveredService>
        fun connect(packageName: String, serviceName: String, onDisconnected: () -> Unit): ConnectedSession?
    }

    private val sessions = ConcurrentHashMap<String, ConnectedSession>()
    private val sessionLocks = ConcurrentHashMap<String, Any>()

    fun readLibraryPlayers(activeByPackage: Map<String, ActiveSessionInfo> = emptyMap()): String {
        val discovered = eligibleServices()
            .sortedWith(
                compareByDescending<DiscoveredService> { activeByPackage.containsKey(it.packageName) }
                    .thenBy { (activeByPackage[it.packageName]?.name ?: it.name).lowercase() }
            )
        return buildJsonObject {
            put("players", JsonArray(discovered.map { svc ->
                val active = activeByPackage[svc.packageName]
                buildJsonObject {
                    put("name", active?.name ?: svc.name)
                    put("packageName", svc.packageName)
                    put("active", active != null)
                    put("canQueue", active?.canQueue ?: false)
                    put("canBrowse", true)
                }
            }))
        }.toString()
    }

    fun browse(
        player: String,
        parentId: String?,
        activeByPackage: Map<String, ActiveSessionInfo> = emptyMap(),
    ): String {
        val service = findService(player, activeByPackage)
            ?: return browseErrorReply(player, "Player '$player' does not expose a media browser service.")

        val session = getOrConnect(service)
            ?: return browseErrorReply(player, "Could not connect to ${service.name} media browser.")

        val targetId = parentId?.takeIf { it.isNotEmpty() } ?: session.rootId
        var children = session.subscribeOnce(targetId)
        var effectiveSession = session
        if (children == null) {
            invalidateSession(service.packageName, session)
            val reconnected = getOrConnect(service)
            if (reconnected != null) {
                effectiveSession = reconnected
                val retryId = parentId?.takeIf { it.isNotEmpty() } ?: reconnected.rootId
                children = reconnected.subscribeOnce(retryId)
                if (children != null) {
                    return browseSuccessReply(
                        player = service.name,
                        rootId = reconnected.rootId,
                        parentId = retryId,
                        searchSupported = reconnected.searchSupported,
                        children = children,
                    )
                }
            }
            return browseErrorReply(service.name, "Failed to load media items for $targetId.")
        }

        return browseSuccessReply(
            player = service.name,
            rootId = effectiveSession.rootId,
            parentId = targetId,
            searchSupported = effectiveSession.searchSupported,
            children = children,
        )
    }

    fun playMediaId(
        player: String,
        mediaId: String,
        activeByPackage: Map<String, ActiveSessionInfo> = emptyMap(),
    ): String {
        if (mediaId.isEmpty()) {
            return playErrorReply("Missing mediaId.")
        }
        val service = findService(player, activeByPackage)
            ?: return playErrorReply("Player '$player' does not expose a media browser service.")

        val session = getOrConnect(service)
            ?: return playErrorReply("Could not connect to ${service.name} media browser.")

        var dispatched = session.playFromMediaId(mediaId)
        if (!dispatched) {
            invalidateSession(service.packageName, session)
            val reconnected = getOrConnect(service)
            if (reconnected != null) {
                dispatched = reconnected.playFromMediaId(mediaId)
            }
        }
        return if (dispatched) {
            buildJsonObject {
                put("accepted", true)
                put("error", JsonNull)
            }.toString()
        } else {
            playErrorReply("Player rejected playFromMediaId for $mediaId.")
        }
    }

    fun errorReply(message: String): String = browseErrorReply("", message)

    fun playErrorReply(message: String): String = buildJsonObject {
        put("accepted", false)
        put("error", message)
    }.toString()

    fun release() {
        for (pkg in sessions.keys.toList()) {
            sessions.remove(pkg)?.let { safeDisconnect(it) }
        }
    }

    private fun eligibleServices(): List<DiscoveredService> {
        val selfPkg = backend.ownPackageName()
        val seen = HashSet<String>()
        return backend.discoverServices().filter { svc ->
            svc.exported &&
                svc.packageName != selfPkg &&
                svc.packageName !in EXCLUDED_PACKAGES &&
                seen.add(svc.packageName)
        }
    }

    private fun findService(
        player: String,
        activeByPackage: Map<String, ActiveSessionInfo>,
    ): DiscoveredService? {
        val services = eligibleServices()
        return services.firstOrNull { svc ->
            val activeName = activeByPackage[svc.packageName]?.name
            svc.name == player || svc.packageName == player || activeName == player
        } ?: services.firstOrNull { svc ->
            val activeName = activeByPackage[svc.packageName]?.name
            svc.name.equals(player, ignoreCase = true) || activeName?.equals(player, ignoreCase = true) == true
        }
    }

    private fun getOrConnect(service: DiscoveredService): ConnectedSession? {
        sessions[service.packageName]?.let { return it }
        val lock = sessionLocks.computeIfAbsent(service.packageName) { Any() }
        synchronized(lock) {
            sessions[service.packageName]?.let { return it }
            lateinit var createdRef: ConnectedSession
            val connected = backend.connect(service.packageName, service.serviceName) {
                sessions.remove(service.packageName)
            } ?: return null
            createdRef = connected
            sessions[service.packageName] = createdRef
            return createdRef
        }
    }

    private fun invalidateSession(packageName: String, expected: ConnectedSession) {
        if (sessions.remove(packageName, expected)) {
            safeDisconnect(expected)
        }
    }

    private fun safeDisconnect(session: ConnectedSession) {
        try {
            session.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting media browser: ${e.message}")
        }
    }

    private fun browseSuccessReply(
        player: String,
        rootId: String,
        parentId: String,
        searchSupported: Boolean,
        children: List<LibraryItem>,
    ): String {
        val limited = children.take(MAX_ITEMS)
        return buildJsonObject {
            put("available", true)
            put("player", player)
            put("rootId", rootId)
            put("parentId", parentId)
            put("searchSupported", searchSupported)
            put("truncated", children.size > MAX_ITEMS)
            put("totalCount", children.size)
            put("error", JsonNull)
            put("items", JsonArray(limited.map { item ->
                buildJsonObject {
                    put("id", item.id)
                    put("title", item.title.take(MAX_TEXT_LEN))
                    put("subtitle", item.subtitle.take(MAX_TEXT_LEN))
                    put("browsable", item.browsable)
                    put("playable", item.playable)
                }
            }))
        }.toString()
    }

    private fun browseErrorReply(player: String, message: String): String = buildJsonObject {
        put("available", false)
        put("player", player)
        put("rootId", JsonNull)
        put("parentId", JsonNull)
        put("searchSupported", false)
        put("truncated", false)
        put("totalCount", 0)
        put("error", message)
        put("items", JsonArray(emptyList()))
    }.toString()

    private class AndroidMediaBrowserBackend(private val context: Context) : BrowserBackend {
        private val mainHandler = Handler(Looper.getMainLooper())

        override fun ownPackageName(): String = context.packageName

        override fun discoverServices(): List<DiscoveredService> {
            val intent = Intent("android.media.browse.MediaBrowserService")
            val flags = PackageManager.MATCH_ALL
            val resolved = context.packageManager.queryIntentServices(intent, flags)
            return resolved.mapNotNull { ri ->
                val si = ri.serviceInfo ?: return@mapNotNull null
                val pkg = si.packageName ?: return@mapNotNull null
                val cls = si.name ?: return@mapNotNull null
                val label = AppsHelper.appNameLookup(context, pkg)
                    .ifBlank { ri.loadLabel(context.packageManager).toString() }
                DiscoveredService(
                    name = label,
                    packageName = pkg,
                    serviceName = cls,
                    exported = si.exported,
                )
            }
        }

        override fun connect(
            packageName: String,
            serviceName: String,
            onDisconnected: () -> Unit,
        ): ConnectedSession? {
            val future = CompletableFuture<ConnectedSession?>()
            val connectedFlag = AtomicBoolean(false)
            val holder = arrayOfNulls<MediaBrowser>(1)

            mainHandler.post {
                try {
                    lateinit var browser: MediaBrowser
                    val callback = object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            if (!connectedFlag.compareAndSet(false, true)) return
                            val root = try {
                                browser.root
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed reading root for $packageName: ${e.message}")
                                null
                            }
                            if (root == null) {
                                browser.disconnect()
                                future.complete(null)
                                return
                            }
                            val extras = try {
                                browser.extras
                            } catch (_: Exception) {
                                null
                            }
                            val searchSupported = extras?.getBoolean(KEY_SEARCH_SUPPORTED, false) ?: false
                            val sessionToken = browser.sessionToken
                            val controller = MediaController(context, sessionToken)
                            future.complete(
                                AndroidConnectedSession(
                                    mainHandler = mainHandler,
                                    browser = browser,
                                    controller = controller,
                                    rootId = root,
                                    searchSupported = searchSupported,
                                )
                            )
                        }

                        override fun onConnectionFailed() {
                            if (connectedFlag.compareAndSet(false, true)) {
                                future.complete(null)
                            }
                            onDisconnected()
                        }

                        override fun onConnectionSuspended() {
                            if (connectedFlag.compareAndSet(false, true)) {
                                future.complete(null)
                            }
                            onDisconnected()
                        }
                    }
                    browser = MediaBrowser(
                        context,
                        ComponentName(packageName, serviceName),
                        callback,
                        null
                    )
                    holder[0] = browser
                    browser.connect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error connecting MediaBrowser to $packageName/$serviceName: ${e.message}")
                    if (connectedFlag.compareAndSet(false, true)) {
                        future.complete(null)
                    }
                }
            }

            return try {
                future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Timeout/error connecting MediaBrowser to $packageName: ${e.message}")
                mainHandler.post {
                    try {
                        holder[0]?.disconnect()
                    } catch (_: Exception) {
                    }
                }
                null
            }
        }
    }

    private class AndroidConnectedSession(
        private val mainHandler: Handler,
        private val browser: MediaBrowser,
        private val controller: MediaController,
        override val rootId: String,
        override val searchSupported: Boolean,
    ) : ConnectedSession {

        override fun subscribeOnce(parentId: String): List<LibraryItem>? {
            val future = CompletableFuture<List<LibraryItem>?>()
            val completed = AtomicBoolean(false)

            mainHandler.post {
                if (!browser.isConnected) {
                    if (completed.compareAndSet(false, true)) {
                        future.complete(null)
                    }
                    return@post
                }
                val callback = object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                        loadedParentId: String,
                        children: MutableList<MediaBrowser.MediaItem>,
                    ) {
                        unsubscribeCompat(parentId, this)
                        if (completed.compareAndSet(false, true)) {
                            val mapped = children.mapNotNull { item ->
                                val id = item.mediaId ?: return@mapNotNull null
                                val d = item.description
                                LibraryItem(
                                    id = id,
                                    title = d.title?.toString().orEmpty(),
                                    subtitle = d.subtitle?.toString().orEmpty(),
                                    browsable = item.isBrowsable,
                                    playable = item.isPlayable,
                                )
                            }
                            future.complete(mapped)
                        }
                    }

                    override fun onError(errorParentId: String) {
                        unsubscribeCompat(parentId, this)
                        if (completed.compareAndSet(false, true)) {
                            future.complete(null)
                        }
                    }
                }
                try {
                    browser.subscribe(parentId, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "subscribe failed for $parentId: ${e.message}")
                    if (completed.compareAndSet(false, true)) {
                        future.complete(null)
                    }
                }
            }

            return try {
                future.get(BROWSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Timeout browsing $parentId: ${e.message}")
                null
            }
        }

        private fun unsubscribeCompat(parentId: String, callback: MediaBrowser.SubscriptionCallback) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    browser.unsubscribe(parentId, callback)
                } else {
                    browser.unsubscribe(parentId)
                }
            } catch (_: Exception) {
            }
        }

        override fun playFromMediaId(mediaId: String): Boolean {
            val future = CompletableFuture<Boolean>()
            mainHandler.post {
                try {
                    if (!browser.isConnected) {
                        future.complete(false)
                        return@post
                    }
                    controller.transportControls.playFromMediaId(mediaId, null)
                    future.complete(true)
                } catch (e: Exception) {
                    Log.w(TAG, "playFromMediaId($mediaId) failed: ${e.message}")
                    future.complete(false)
                }
            }
            return try {
                future.get(PLAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                false
            }
        }

        override fun disconnect() {
            mainHandler.post {
                try {
                    if (browser.isConnected) {
                        browser.disconnect()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "MprisLibrary"
        private const val KEY_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val MAX_ITEMS = 1000
        private const val MAX_TEXT_LEN = 512
        private const val CONNECT_TIMEOUT_MS = 5000L
        private const val BROWSE_TIMEOUT_MS = 5000L
        private const val PLAY_TIMEOUT_MS = 2500L

        private val EXCLUDED_PACKAGES = setOf(
            "com.android.bluetooth",
        )
    }
}
