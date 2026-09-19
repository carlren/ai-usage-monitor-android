package com.carlren.aiusagemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf

object CadenceBridgeRuntime {
    val state = mutableStateOf(CadenceBridgeState())
}

class CadenceBridgeService : Service() {
    companion object {
        private const val CHANNEL_ID = "cadence_bridge"
        private const val NOTIFICATION_ID = 18_514
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bridge: CadenceBridge? = null
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                Log.i("CadenceBridge", "Bluetooth restarted; rebuilding RSC advertiser")
                bridge?.stop()
                startBridge()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(CadenceBridgeRuntime.state.value))
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        startBridge()
    }

    private fun startBridge() {
        bridge = CadenceBridge(applicationContext) { state ->
            handler.post {
                CadenceBridgeRuntime.state.value = state
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(state))
            }
        }.also(CadenceBridge::start)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(bluetoothStateReceiver)
        bridge?.stop()
        bridge = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zwift cadence bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the virtual running cadence sensor available"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(state: CadenceBridgeState): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val connection = when {
            state.zwiftConnected -> "Zwift connected"
            state.advertising -> "waiting for Zwift"
            else -> "starting Bluetooth"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RUN CADENCE BRIDGE")
            .setContentText("${state.cadenceSpm} SPM · $connection")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}

class CadenceBridgeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val serviceIntent = Intent(context, CadenceBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
