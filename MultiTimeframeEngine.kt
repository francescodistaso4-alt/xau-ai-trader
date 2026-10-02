package com.xauaitrader.app.engine

object MultiTimeframeEngine {
    data class TimeframeAnalysis(val timeframe: String, val analysis: IctSmcEngine.Analysis)
    data class Result(val analyses: List<TimeframeAnalysis>, val alignedBias: IctSmcEngine.Bias, val setup: IctSmcEngine.Setup?, val score: Int)

    fun analyze(data: Map<String, List<IctSmcEngine.Candle>>): Result {
        val order = listOf("H4", "H1", "M15", "M5")
        val analyses = order.mapNotNull { tf -> data[tf]?.let { TimeframeAnalysis(tf, IctSmcEngine.analyze(it)) } }
        val bullish = analyses.count { it.analysis.bias == IctSmcEngine.Bias.BULLISH }
        val bearish = analyses.count { it.analysis.bias == IctSmcEngine.Bias.BEARISH }
        val aligned = when { bullish >= 3 && bullish > bearish -> IctSmcEngine.Bias.BULLISH; bearish >= 3 && bearish > bullish -> IctSmcEngine.Bias.BEARISH; else -> IctSmcEngine.Bias.NEUTRAL }
        val base = analyses.lastOrNull()?.analysis?.setup
        val filtered = base?.takeIf { (aligned == IctSmcEngine.Bias.BULLISH && it.direction == IctSmcEngine.Direction.LONG) || (aligned == IctSmcEngine.Bias.BEARISH && it.direction == IctSmcEngine.Direction.SHORT) }
        return Result(analyses, aligned, filtered, maxOf(bullish, bearish) * 25)
    }
}
