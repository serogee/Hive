package io.github.serogee.hive.data.server

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle

/** Checks the server when the network changes and when the app returns to the foreground. */
class ServerTriggers(application: Application, private val monitor: ServerMonitor) {
    private var startedActivities = 0

    init {
        // Not every phone allows the callback. The other checks work without it.
        runCatching {
            application.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = monitor.checkNow()

                    override fun onLost(network: Network) = monitor.checkNow()
                },
            )
        }
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (startedActivities++ == 0) monitor.checkNow()
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities--
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
