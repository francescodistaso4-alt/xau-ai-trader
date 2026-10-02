package com.xauaitrader.app.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Deterministic ICT/SMC analysis engine. It creates analysis levels only; it never sends orders. */
object IctSmcEngine {
    data class Candle(
        val time: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double = 0.0
    )

    enum class Bias { BULLISH, BEARISH, NEUTRAL }
    enum class Direction { LONG, SHORT }
    enum class SetupType { FVG_RETEST, ORDER_BLOCK_RETEST, LIQUIDITY_SWEEP }

    data class Swing(val index: Int, val price: Double, val high: Boolean)
    data class Fvg(val startIndex: Int, val endIndex: Int, val low: Double, val high: Double, val bullish: Boolean)
    data class OrderBlock(val index: Int, val low: Double, val high: Double, val bullish: Boolean)
    data class LiquiditySweep(val index: Int, val level: Double, val bullish: Boolean)
    data class Structure(val bias: Bias, val bos: Boolean, val choch: Boolean, val lastSwingHigh: Double?, val lastSwingLow: Double?)

    data class Setup(
        val direction: Direction,
        val type: SetupType,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit1: Double,
        val takeProfit2: Double,
        val takeProfit3: Double,
        val riskRewardTp2: Double,
        val confidence: Int,
        val reasons: List<String>
    )

    data class Analysis(
        val bias: Bias,
        val structure: Structure,
        val swings: List<Swing>,
        val fvgs: List<Fvg>,
        val orderBlocks: List<OrderBlock>,
        val sweeps: List<LiquiditySweep>,
        val premiumDiscountEquilibrium: Double?,
        val setup: Setup?
    )

    fun analyze(candles: List<Candle>, swingLength: Int = 3): Analysis {
        if (candles.size < swingLength * 2 + 5) {
            return Analysis(Bias.NEUTRAL, Structure(Bias.NEUTRAL, false, false, null, null), emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        }

        val swings = findSwings(candles, swingLength)
        val highs = swings.filter { it.high }
        val lows = swings.filter { !it.high }
        val lastHigh = highs.lastOrNull()?.price
        val lastLow = lows.lastOrNull()?.price
        val structure = detectStructure(candles, highs, lows)
        val fvgs = findFvgs(candles)
        val obs = findOrderBlocks(candles, structure)
        val sweeps = findLiquiditySweeps(candles, highs, lows)
        val eq = if (lastHigh != null && lastLow != null) (lastHigh + lastLow) / 2.0 else null
        val setup = buildSetup(candles, structure, fvgs, obs, sweeps, eq)
        return Analysis(structure.bias, structure, swings, fvgs, obs, sweeps, eq, setup)
    }

    private fun findSwings(c: List<Candle>, n: Int): List<Swing> {
        val result = mutableListOf<Swing>()
        for (i in n until c.size - n) {
            val h = c[i].high
            val l = c[i].low
            val isHigh = (i - n until i).all { c[it].high < h } && (i + 1..i + n).all { c[it].high < h }
            val isLow = (i - n until i).all { c[it].low > l } && (i + 1..i + n).all { c[it].low > l }
            if (isHigh) result += Swing(i, h, true)
            if (isLow) result += Swing(i, l, false)
        }
        return result.sortedBy { it.index }
    }

    private fun detectStructure(c: List<Candle>, highs: List<Swing>, lows: List<Swing>): Structure {
        val last = c.last().close
        val previousHigh = highs.dropLast(1).lastOrNull()?.price
        val previousLow = lows.dropLast(1).lastOrNull()?.price
        val lastHigh = highs.lastOrNull()?.price
        val lastLow = lows.lastOrNull()?.price
        val bullishBreak = previousHigh != null && last > previousHigh
        val bearishBreak = previousLow != null && last < previousLow
        val bias = when {
            bullishBreak && !bearishBreak -> Bias.BULLISH
            bearishBreak && !bullishBreak -> Bias.BEARISH
            lastHigh != null && lastLow != null && last > (lastHigh + lastLow) / 2.0 -> Bias.BULLISH
            lastHigh != null && lastLow != null && last < (lastHigh + lastLow) / 2.0 -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }
        val choch = (bullishBreak && previousLow != null && last < previousLow) || (bearishBreak && previousHigh != null && last > previousHigh)
        return Structure(bias, bullishBreak || bearishBreak, choch, lastHigh, lastLow)
    }

    private fun findFvgs(c: List<Candle>): List<Fvg> {
        val result = mutableListOf<Fvg>()
        for (i in 2 until c.size) {
            val left = c[i - 2]
            val right = c[i]
            if (right.low > left.high) result += Fvg(i - 2, i, left.high, right.low, true)
            if (right.high < left.low) result += Fvg(i - 2, i, right.high, left.low, false)
        }
        return result.takeLast(20)
    }

    private fun findOrderBlocks(c: List<Candle>, structure: Structure): List<OrderBlock> {
        val result = mutableListOf<OrderBlock>()
        for (i in 1 until c.lastIndex) {
            val cur = c[i]
            val next = c[i + 1]
            val bullishImpulse = next.close > next.open && next.close > cur.high
            val bearishImpulse = next.close < next.open && next.close < cur.low
            if (bullishImpulse && cur.close < cur.open) result += OrderBlock(i, cur.low, cur.high, true)
            if (bearishImpulse && cur.close > cur.open) result += OrderBlock(i, cur.low, cur.high, false)
        }
        return result.takeLast(20)
    }

    private fun findLiquiditySweeps(c: List<Candle>, highs: List<Swing>, lows: List<Swing>): List<LiquiditySweep> {
        val result = mutableListOf<LiquiditySweep>()
        for (i in 1 until c.size) {
            val highRef = highs.lastOrNull { it.index < i }
            val lowRef = lows.lastOrNull { it.index < i }
            if (highRef != null && c[i].high > highRef.price && c[i].close < highRef.price) {
                result += LiquiditySweep(i, highRef.price, false)
            }
            if (lowRef != null && c[i].low < lowRef.price && c[i].close > lowRef.price) {
                result += LiquiditySweep(i, lowRef.price, true)
            }
        }
        return result.takeLast(10)
    }

    private fun buildSetup(
        c: List<Candle>, structure: Structure, fvgs: List<Fvg>, obs: List<OrderBlock>, sweeps: List<LiquiditySweep>, eq: Double?
    ): Setup? {
        val last = c.last()
        val range = max(last.high - last.low, 0.01)
        val bullishSweep = sweeps.lastOrNull { it.bullish }
        val bearishSweep = sweeps.lastOrNull { !it.bullish }
        val bullishFvg = fvgs.lastOrNull { it.bullish && last.low <= it.high && last.close >= it.low }
        val bearishFvg = fvgs.lastOrNull { !it.bullish && last.high >= it.low && last.close <= it.high }
        val bullishOb = obs.lastOrNull { it.bullish && last.low <= it.high && last.close >= it.low }
        val bearishOb = obs.lastOrNull { !it.bullish && last.high >= it.low && last.close <= it.high }

        val longScore = (if (structure.bias == Bias.BULLISH) 30 else 0) + (if (bullishSweep != null) 25 else 0) + (if (bullishFvg != null) 25 else 0) + (if (bullishOb != null) 15 else 0) + (if (eq != null && last.close < eq) 5 else 0)
        val shortScore = (if (structure.bias == Bias.BEARISH) 30 else 0) + (if (bearishSweep != null) 25 else 0) + (if (bearishFvg != null) 25 else 0) + (if (bearishOb != null) 15 else 0) + (if (eq != null && last.close > eq) 5 else 0)

        if (max(longScore, shortScore) < 55) return null
        val long = longScore >= shortScore
        val entry = last.close
        val stop = if (long) {
            val anchor = listOfNotNull(bullishSweep?.level, bullishOb?.low, bullishFvg?.low).minOrNull() ?: last.low
            anchor - range * 0.20
        } else {
            val anchor = listOfNotNull(bearishSweep?.level, bearishOb?.high, bearishFvg?.high).maxOrNull() ?: last.high
            anchor + range * 0.20
        }
        val risk = abs(entry - stop)
        if (risk <= 0.0) return null
        val tp1 = if (long) entry + risk * 1.0 else entry - risk * 1.0
        val tp2 = if (long) entry + risk * 2.0 else entry - risk * 2.0
        val tp3 = if (long) entry + risk * 3.0 else entry - risk * 3.0
        val reasons = mutableListOf<String>()
        reasons += if (long) "Bias bullish" else "Bias bearish"
        if (if (long) bullishSweep != null else bearishSweep != null) reasons += "Liquidity sweep"
        if (if (long) bullishFvg != null else bearishFvg != null) reasons += "FVG"
        if (if (long) bullishOb != null else bearishOb != null) reasons += "Order Block"
        reasons += if (long) "Target su multipli di rischio" else "Target su multipli di rischio"
        return Setup(
            if (long) Direction.LONG else Direction.SHORT,
            if (if (long) bullishFvg != null else bearishFvg != null) SetupType.FVG_RETEST else if (if (long) bullishOb != null else bearishOb != null) SetupType.ORDER_BLOCK_RETEST else SetupType.LIQUIDITY_SWEEP,
            entry, stop, tp1, tp2, tp3, 2.0, min(99, max(longScore, shortScore)), reasons
        )
    }
}
