package com.xauaitrader.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xauaitrader.app.engine.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { XauAiTraderApp() }
    }
}

@Composable
fun XauAiTraderApp() {
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(bottomBar = {
            NavigationBar { listOf("Home", "Analisi", "Backtest", "Diario").forEachIndexed { i, label ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(label) })
            }}
        }) { pad ->
            when (tab) { 0 -> HomeScreen(Modifier.padding(pad)); 1 -> AnalysisScreen(Modifier.padding(pad)); 2 -> BacktestScreen(Modifier.padding(pad)); else -> DiaryScreen(Modifier.padding(pad)) }
        }
    }
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wsUrl = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("ws", "ws://10.0.2.2:8080/ws/market") ?: "ws://10.0.2.2:8080/ws/market"
    val feed = remember(wsUrl) { LiveMarketFeed(wsUrl) }
    val status by feed.status.collectAsState()
    val liveBar by feed.latestBar.collectAsState()
    val candles = remember(liveBar) { DemoMarketData.candles().toMutableList().apply { liveBar?.let { add(it) } } }
    val analysis = remember(candles) { IctSmcEngine.analyze(candles) }
    val setup = analysis.setup
    val notifier = remember { SignalNotificationManager(context) }
    var lastSignature by remember { mutableStateOf(context.getSharedPreferences("alerts", Context.MODE_PRIVATE).getString("last", null)) }
    var monitorOn by remember { mutableStateOf(false) }; var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(setup) {
        if (setup != null) {
            val sig = "${setup.direction}|${setup.type}|${"%.2f".format(setup.entry)}|${"%.2f".format(setup.stopLoss)}|${"%.2f".format(setup.takeProfit2)}"
            if (sig != lastSignature) { notifier.notifySignal(setup); context.getSharedPreferences("alerts", Context.MODE_PRIVATE).edit().putString("last", sig).apply(); lastSignature = sig }
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("XAU AI TRADER", fontSize = 26.sp); Text("Signal-only • ICT + SMC", color = MaterialTheme.colorScheme.primary) }; TextButton(onClick = { showSettings = true }) { Text("Impostazioni") } } }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("XAUUSD", fontSize = 19.sp); Text(if (status.connected) "● LIVE • ${status.message}" else "○ ${status.message}")
                Spacer(Modifier.height(10.dp)); PriceChart(candles.takeLast(80))
                if (showSettings) SettingsDialog(context, onDismiss = { showSettings = false })
                Spacer(Modifier.height(8.dp)); Text("Prezzo: ${liveBar?.close?.let { "%.2f".format(it) } ?: "—"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { feed.connect() }, enabled = !status.connected) { Text("Connetti") }; OutlinedButton(onClick = { feed.disconnect() }, enabled = status.connected) { Text("Disconnetti") } }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = monitorOn, onCheckedChange = { on -> monitorOn = on; if (on) context.startForegroundService(Intent(context, SignalMonitorService::class.java)) else context.stopService(Intent(context, SignalMonitorService::class.java)) }); Spacer(Modifier.width(8.dp)); Text("Monitoraggio notifiche 24/7") }
            }}
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Analisi ICT/SMC", fontSize = 19.sp); Text("Bias: ${analysis.bias}"); Text("BOS: ${analysis.structure.bos} • CHoCH: ${analysis.structure.choch}")
                Text("FVG: ${analysis.fvgs.size} • OB: ${analysis.orderBlocks.size} • Sweep: ${analysis.sweeps.size}")
            }}
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Segnale", fontSize = 19.sp)
                if (setup == null) Text("Nessun setup con conferma sufficiente.") else {
                    Text(if (setup.direction == IctSmcEngine.Direction.LONG) "🟢 ACQUISTO" else "🔴 VENDITA", fontSize = 21.sp)
                    Text("Entry ${"%.2f".format(setup.entry)} • SL ${"%.2f".format(setup.stopLoss)}")
                    Text("TP1 ${"%.2f".format(setup.takeProfit1)} • TP2 ${"%.2f".format(setup.takeProfit2)} • TP3 ${"%.2f".format(setup.takeProfit3)}")
                    Text("R:R 1:${"%.1f".format(setup.riskRewardTp2)} • Conferma ${setup.confidence}%")
                    Text(setup.reasons.joinToString(" • "))
                }
            }}
        }
        item { Text("🔔 Le notifiche avvisano sui nuovi segnali. L'app non apre, modifica o chiude ordini.", fontSize = 12.sp) }
    }
}

@Composable fun PriceChart(candles: List<IctSmcEngine.Candle>) {
    if (candles.size < 2) return
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val minP = candles.minOf { it.low }; val maxP = candles.maxOf { it.high }; val span = (maxP - minP).coerceAtLeast(0.01)
        val path = Path()
        candles.forEachIndexed { i, c -> val x = size.width * i / (candles.lastIndex.coerceAtLeast(1)); val y = size.height * (1f - ((c.close - minP) / span).toFloat()); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path)
    }
}

@Composable
private fun SettingsDialog(context: Context, onDismiss: () -> Unit) {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var api by remember { mutableStateOf(prefs.getString("api", "http://10.0.2.2:8080") ?: "") }
    var ws by remember { mutableStateOf(prefs.getString("ws", "ws://10.0.2.2:8080/ws/market") ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connessione backend") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(api, { api = it }, label = { Text("API base URL") }); OutlinedTextField(ws, { ws = it }, label = { Text("WebSocket URL") }); Text("In produzione usa HTTPS/WSS e un backend autenticato.", fontSize = 12.sp)
    } }, confirmButton = { Button(onClick = { prefs.edit().putString("api", api.trim()).putString("ws", ws.trim()).apply(); onDismiss() }) { Text("Salva") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}

@Composable
fun AnalysisScreen(modifier: Modifier = Modifier) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri = it }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ANALISI GRAFICO", fontSize = 25.sp); Text("Carica uno screenshot/foto del grafico per l'analisi visiva.")
        Button(onClick = { picker.launch("image/*") }) { Text("Seleziona screenshot") }
        uri?.let { Text("Immagine selezionata: ${it.lastPathSegment ?: "grafico"}") }
        OutlinedButton(onClick = {}, enabled = uri != null) { Text("Analizza con AI") }
        Text("Il connettore vision è predisposto nel backend; non vengono inventati livelli se l'analisi visiva non è disponibile.", fontSize = 12.sp)
    }
}

@Composable
fun BacktestScreen(modifier: Modifier = Modifier) {
    var result by remember { mutableStateOf<Backtester.Result?>(null) }
    val candles = remember { DemoMarketData.candles() }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("BACKTEST", fontSize = 25.sp); Text("ICT + SMC • rischio 1% • spread/slippage inclusi")
        Button(onClick = { result = Backtester.run(candles) }) { Text("Avvia backtest") }
        result?.let { r -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Operazioni: ${r.trades.size}"); Text("Win rate: ${"%.1f".format(r.winRate)}%"); Text("P/L: €${"%.2f".format(r.netPnl)}"); Text("Max drawdown: €${"%.2f".format(r.maxDrawdown)}")
        } } }
        Text("Il backtest usa solo informazioni disponibili prima di ogni barra e tratta SL prima di TP quando entrambi sono toccati nella stessa candela.", fontSize = 12.sp)
    }
}

@Composable
fun DiaryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current; val store = remember { DiaryStore(context) }; var entries by remember { mutableStateOf(store.all()) }
    var show by remember { mutableStateOf(false) }
    val month = YearMonth.now(); val monthEntries = entries.filter { it.date.startsWith(month.toString()) }; val total = monthEntries.sumOf { it.pnl }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DIARIO", fontSize = 25.sp); Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Totale mese: €${"%.2f".format(total)}", fontSize = 21.sp); Text("Giorni positivi: ${monthEntries.count { it.pnl > 0 }} • negativi: ${monthEntries.count { it.pnl < 0 }}") } }
        Button(onClick = { show = true }) { Text("Aggiungi risultato giornaliero") }
        itemsForDiary(entries.sortedByDescending { it.date })
    }
    if (show) DiaryDialog(onDismiss = { show = false }) { date, pnl, note -> store.add(DiaryStore.Entry(date, pnl, note)); entries = store.all(); show = false }
}

@Composable private fun ColumnScope.itemsForDiary(entries: List<DiaryStore.Entry>) { entries.take(20).forEach { e -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(e.date); Text("${if (e.pnl >= 0) "+" else ""}€${"%.2f".format(e.pnl)}"); if (e.note.isNotBlank()) Text(e.note, fontSize = 12.sp) } } } }

@Composable
fun DiaryDialog(onDismiss: () -> Unit, onSave: (String, Double, String) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }; var pnl by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Risultato giornaliero") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(date, { date = it }, label = { Text("Data YYYY-MM-DD") }); OutlinedTextField(pnl, { pnl = it }, label = { Text("Profit/Loss €") }); OutlinedTextField(note, { note = it }, label = { Text("Nota") })
    } }, confirmButton = { Button(onClick = { pnl.toDoubleOrNull()?.let { onSave(date, it, note) } }, enabled = pnl.toDoubleOrNull() != null) { Text("Salva") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } })
}
