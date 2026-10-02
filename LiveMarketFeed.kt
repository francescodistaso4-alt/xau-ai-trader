package com.xauaitrader.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Signal-only market stream. It receives normalized bars from our backend; it cannot place orders. */
class LiveMarketFeed(private val wsUrl: String) {
    data class Status(val connected: Boolean, val message: String)
    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private val _status = MutableStateFlow(Status(false, "Non connesso"))
    val status: StateFlow<Status> = _status
    private val _bars = MutableStateFlow<IctSmcEngine.Candle?>(null)
    val latestBar: StateFlow<IctSmcEngine.Candle?> = _bars

    fun connect() {
        if (socket != null) return
        _status.value = Status(false, "Connessione…")
        socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                _status.value = Status(true, "Feed connesso")
                webSocket.send("{\"timeframes\":[\"M5\",\"M15\",\"H1\",\"H4\"]}")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    if (o.optString("type") == "connected") return
                    val tf = o.optString("timeframe")
                    if (tf == "M5") {
                        _bars.value = IctSmcEngine.Candle(o.getLong("time"),o.getDouble("open"),o.getDouble("high"),o.getDouble("low"),o.getDouble("close"),o.optDouble("volume",0.0))
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                _status.value = Status(false, "Feed offline: ${t.message ?: "errore"}")
                socket = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = Status(false, "Feed disconnesso")
                socket = null
            }
        })
    }
    fun disconnect() { socket?.close(1000, "client close"); socket = null; _status.value = Status(false,"Non connesso") }
}
