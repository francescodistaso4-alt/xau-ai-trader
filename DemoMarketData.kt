package com.xauaitrader.app.engine

object DemoMarketData {
    fun candles(): List<IctSmcEngine.Candle> {
        val closes = listOf(2650.0,2652.0,2648.5,2655.0,2658.0,2654.0,2662.0,2666.0,2663.0,2670.0,2674.0,2671.0,2678.0,2681.0,2676.0,2684.0,2688.0,2683.0,2691.0,2695.0,2690.0,2698.0,2702.0,2697.0,2705.0,2709.0,2704.0,2712.0,2716.0,2711.0)
        return closes.mapIndexed { i, close ->
            val prev = if (i == 0) close - 1.0 else closes[i - 1]
            val open = prev
            val high = maxOf(open, close) + 1.2
            val low = minOf(open, close) - 1.0
            IctSmcEngine.Candle(i.toLong(), open, high, low, close)
        }
    }
}
