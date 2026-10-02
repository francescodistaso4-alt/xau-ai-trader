package com.xauaitrader.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xauaitrader.app.engine.IctSmcEngine

/**
 * Local signal notifications only. It never contains or exposes any order-execution API.
 */
class SignalNotificationManager(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "xau_signal_alerts"
        private const val CHANNEL_NAME = "Segnali XAUUSD"
        private const val NOTIFICATION_ID = 7001
    }

    init {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avvisi quando il motore ICT/SMC rileva un nuovo segnale XAUUSD"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifySignal(setup: IctSmcEngine.Setup) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val isBuy = setup.direction == IctSmcEngine.Direction.LONG
        val title = if (isBuy) "🟢 Segnale ACQUISTO XAUUSD" else "🔴 Segnale VENDITA XAUUSD"
        val text = "Entry %.2f • SL %.2f • TP2 %.2f • R:R 1:%.1f".format(
            setup.entry, setup.stopLoss, setup.takeProfit2, setup.riskRewardTp2
        )
        val details = setup.reasons.joinToString(" • ")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nConferma ${setup.confidence}%\n$details"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
