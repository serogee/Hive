package io.github.serogee.hive.localhost

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Finds rooms that other phones host on the same network. */
fun interface NearbyRooms {
    /** The address of the phone that hosts the room with [code], or null when none answers in time. */
    suspend fun find(code: String): String?
}

/** How a room on a phone is named on the local network. */
object NearbyService {
    const val TYPE = "_hive._tcp"

    /** The TXT entry that holds the room code. */
    const val CODE_KEY = "code"

    private const val PREFIX = "Hive "

    fun nameFor(code: String) = PREFIX + code

    /** Whether [name] can belong to [code]. Android adds a number, such as " (2)", when a name is taken. */
    fun mayBe(name: String, code: String) = name.startsWith(nameFor(code))
}

/**
 * Announces this phone's rooms with DNS-SD, and finds rooms on other phones. It needs no permission
 * while the app targets Android 16. Apps that target Android 17 need ACCESS_LOCAL_NETWORK for it.
 *
 * Networks that keep devices apart block it, so every failure here means only that nothing was found.
 */
class NsdNearbyRooms(context: Context) : NearbyRooms {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val announced = mutableMapOf<String, NsdManager.RegistrationListener>()

    /** Announces the rooms with [codes], and nothing else. */
    @Synchronized
    fun announce(codes: Set<String>) {
        (announced.keys - codes).forEach { code ->
            val listener = announced.remove(code) ?: return@forEach
            runCatching { nsd.unregisterService(listener) }
        }
        (codes - announced.keys).forEach { code ->
            val info = NsdServiceInfo().apply {
                serviceName = NearbyService.nameFor(code)
                serviceType = NearbyService.TYPE
                port = LocalHost.PORT
                setAttribute(NearbyService.CODE_KEY, code)
            }
            val listener = AnnounceListener()
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onSuccess { announced[code] = listener }
        }
    }

    override suspend fun find(code: String): String? = withTimeoutOrNull(FIND_MILLIS) {
        services()
            .filter { NearbyService.mayBe(it.serviceName, code) }
            .mapNotNull { resolve(it) }
            .mapNotNull { it.addressFor(code) }
            .firstOrNull()
    }

    private fun services(): Flow<NsdServiceInfo> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                trySend(service)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices(NearbyService.TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { close() }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** Older Android versions resolve one service at a time, so a busy resolver is asked again. */
    private suspend fun resolve(service: NsdServiceInfo): NsdServiceInfo? {
        repeat(RESOLVE_ATTEMPTS) {
            resolveOnce(service)?.let { return it }
            delay(RESOLVE_PAUSE_MILLIS)
        }
        return null
    }

    @Suppress("DEPRECATION") // The replacement needs Android 14.
    private suspend fun resolveOnce(service: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (continuation.isActive) continuation.resume(resolved)
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
        runCatching { nsd.resolveService(service, listener) }.onFailure { if (continuation.isActive) continuation.resume(null) }
    }

    @Suppress("DEPRECATION") // hostAddresses needs Android 14.
    private fun NsdServiceInfo.addressFor(code: String): String? {
        val announcedCode = attributes[NearbyService.CODE_KEY]?.decodeToString()
        if (announcedCode != null && announcedCode != code) return null
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) hostAddresses else listOfNotNull(host)
        val address = addresses.filterIsInstance<Inet4Address>().firstOrNull() ?: return null
        return "http://${address.hostAddress}:$port"
    }

    private class AnnounceListener : NsdManager.RegistrationListener {
        override fun onServiceRegistered(service: NsdServiceInfo) = Unit

        override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit

        override fun onServiceUnregistered(service: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
    }

    private companion object {
        const val FIND_MILLIS = 5_000L
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_PAUSE_MILLIS = 200L
    }
}
