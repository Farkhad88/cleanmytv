package dev.cleanmytv.companion

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class CompanionApp : Application()

/** Keeps approved transfers alive in the foreground. A killed process leaves a recovery journal. */
class OperationService : Service() {
    private var lock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("operations", "TV operations", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(7, Notification.Builder(this, "operations").setSmallIcon(R.drawable.ic_app).setContentTitle("CleanMyTV")
            .setContentText("An approved TV operation is running. Keep this phone on Wi-Fi.").setContentIntent(open).setOngoing(true).build())
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanMyTV:operation").apply { acquire(30 * 60_000L) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { lock?.let { if(it.isHeld) it.release() }; super.onDestroy() }
}
