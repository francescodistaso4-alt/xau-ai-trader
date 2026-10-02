package com.xauaitrader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xauaitrader.app.engine.IctSmcEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.concurrent.thread

/** Optional foreground monitor. It only calculates signals and notifications; it has no broker/order API. */
class SignalMonitorService : Service() {
    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private val candles = ArrayDeque<IctSmcEngine.Candle>()
    private var lastSignal: String? = null

    override fun onCreate() {
        super.onCreate(); createChannel(); startForeground(7100, serviceNotification("Monitoraggio XAUUSD attivo")); connect()
    }
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("xau_monitor", "Monitoraggio XAUUSD", NotificationManager.IMPORTANCE_LOW))
    }
    private fun serviceNotification(text: String): Notification = NotificationCompat.Builder(this, "xau_monitor")
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("XAU AI Trader").setContentText(text).setOngoing(true).build()
    private fun loadHistory() {
        thread {
            runCatching {
                val base = getSharedPreferences("settings", MODE_PRIVATE).getString("api", "http://10.0.2.2:8080") ?: return@thread
                val body = client.newCall(Request.Builder().url("$base/bars?symbol=XAUUSD&timeframe=M5&start=0&end=2147483647&limit=500").build()).execute().use { it.body?.string() ?: "" }
                val arr = JSONObject(body).optJSONArray("bars") ?: return@thread
                synchronized(candles) {
                    for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); candles.addLast(IctSmcEngine.Candle(o.getLong("time"), o.getDouble("open"), o.getDouble("high"), o.getDouble("low"), o.getDouble("close"), o.optDouble("volume", 0.0))) }
                    while (candles.size > 500) candles.removeFirst()
                }
            }
        }
    }
    private fun connect() {
        loadHistory()
        val url = getSharedPreferences("settings", MODE_PRIVATE).getString("ws", "ws://10.0.2.2:8080/ws/market") ?: return
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = JSONObject(text); if (o.optString("timeframe") != "M5") return
                    val c = IctSmcEngine.Candle(o.getLong("time"), o.getDouble("open"), o.getDouble("high"), o.getDouble("low"), o.getDouble("close"), o.optDouble("volume", 0.0))
                    if (candles.lastOrNull()?.time == c.time) candles.removeLast()
                    candles.addLast(c); while (candles.size > 500) candles.removeFirst()
                    val setup = IctSmcEngine.analyze(candles.toList()).setup ?: return
                    val sig = "${setup.direction}|${setup.type}|${"%.2f".format(setup.entry)}|${"%.2f".format(setup.stopLoss)}|${"%.2f".format(setup.takeProfit2)}"
                    if (sig != lastSignal) { SignalNotificationManager(this@SignalMonitorService).notifySignal(setup); lastSignal = sig }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { stopSelf() }
        })
    }
    override fun onDestroy() { socket?.close(1000, "service stopped"); socket = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
