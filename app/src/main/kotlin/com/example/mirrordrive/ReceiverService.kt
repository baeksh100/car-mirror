package com.example.mirrordrive

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ReceiverService : Service() {
    private var discovery: DiscoveryService? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        // Native init can return false or throw (unloadable/incompatible native lib). Don't let it
        // crash the whole process — log it and stop the service gracefully. The activity's native
        // gate already keeps the UI alive; this is the belt-and-braces path for the service.
        val ok = try {
            NativeBridge.nativeInit(filesDir.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeInit threw", t)
            false
        }
        if (!ok) {
            Log.e(TAG, "native init failed; stopping ReceiverService (mirroring unavailable)")
            stopSelf()
            return
        }
        acquireWifiLock()
        startDiscovery()
    }

    /** Advertise under the user-chosen name; called again (after stop) when it is renamed. */
    private fun startDiscovery() {
        discovery = DiscoveryService(this).also {
            it.start(
                name = CarSettings.receiverName(this),
                port = NativeBridge.nativeGetPort(),
                pkHex = NativeBridge.nativeGetPublicKeyHex(),
                deviceId = NativeBridge.nativeGetDeviceId(),
            )
        }
    }

    override fun onDestroy() {
        discovery?.stop()
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    // Wi-Fi power-save adds tens of ms of jitter per packet on many chipsets; mirroring is a
    // constant real-time stream, so hold a low-latency lock while the receiver is running.
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() = runCatching {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "carmirror-stream").apply { setReferenceCounted(false); acquire() }
    }.onFailure { Log.w(TAG, "wifi lock unavailable", it) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_RENAME && discovery != null) {
            discovery?.stop()
            startDiscovery()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val chId = "carmirror"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "CarMirror", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, chId)
            .setContentTitle("CarMirror").setContentText("아이폰 화면 미러링 대기 중")
            .setSmallIcon(android.R.drawable.stat_sys_upload).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        private const val TAG = "ReceiverService"
        const val ACTION_RENAME = "carmirror.RENAME"
    }
}
