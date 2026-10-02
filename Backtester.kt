package com.xauaitrader.app.engine

import kotlin.math.max

object Backtester {
    data class Config(val initialBalance: Double = 10_000.0, val riskPercent: Double = 1.0, val spread: Double = 0.20, val slippage: Double = 0.05)
    data class Trade(val direction: IctSmcEngine.Direction, val entry: Double, val sl: Double, val tp: Double, val pnlR: Double, val index: Int)
    data class Result(val trades: List<Trade>, val finalBalance: Double, val winRate: Double, val netPnl: Double, val maxDrawdown: Double)

    fun run(candles: List<IctSmcEngine.Candle>, config: Config = Config()): Result {
        if (candles.size < 80) return Result(emptyList(), config.initialBalance, 0.0, 0.0, 0.0)
        var balance = config.initialBalance
        var peak = balance
        var maxDd = 0.0
        val trades = mutableListOf<Trade>()
        var i = 40
        while (i < candles.size - 1) {
            val analysis = IctSmcEngine.analyze(candles.subList(0, i + 1))
            val setup = analysis.setup
            if (setup == null) { i++; continue }
            val risk = kotlin.math.abs(setup.entry - setup.stopLoss)
            if (risk <= 0.0) { i++; continue }
            val riskMoney = balance * (config.riskPercent / 100.0)
            val units = riskMoney / risk
            var outcome: Double? = null
            for (j in i + 1 until candles.size) {
                val b = candles[j]
                val slHit: Boolean
                val tpHit: Boolean
                if (setup.direction == IctSmcEngine.Direction.LONG) {
                    slHit = b.low <= setup.stopLoss
                    tpHit = b.high >= setup.takeProfit2
                } else {
                    slHit = b.high >= setup.stopLoss
                    tpHit = b.low <= setup.takeProfit2
                }
                // Conservative rule: if SL and TP occur in the same candle, count SL first.
                if (slHit) { outcome = -1.0; i = j; break }
                if (tpHit) { outcome = setup.riskRewardTp2; i = j; break }
            }
            if (outcome != null) {
                val friction = (config.spread + config.slippage) * units
                val pnl = outcome * riskMoney - friction
                balance += pnl
                trades += Trade(setup.direction, setup.entry, setup.stopLoss, setup.takeProfit2, outcome, i)
                peak = max(peak, balance)
                maxDd = max(maxDd, peak - balance)
            } else break
            i++
        }
        val wins = trades.count { it.pnlR > 0 }
        val net = balance - config.initialBalance
        return Result(trades, balance, if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size, net, maxDd)
    }
}
