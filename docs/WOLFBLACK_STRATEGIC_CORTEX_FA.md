# WOLFBLACK Strategic Cortex

## Components
- Liquidity map: estimates buy-side/sell-side stop clusters from repeated swing highs/lows, sweep-and-reclaim and break-and-hold events. These are candle-derived estimates, not direct access to exchange stop orders.
- Sentiment: currently a transparent market-behaviour proxy built from price change, RSI/MFI, relative volume and fresh internal flow when present. It does not claim news/social sentiment without a structured and sourced feed.
- Strategy recognition/generation: sweep-reclaim reversal, breakout-retest, trend pullback, range mean reversion and no-trade.
- Scenario search: two-ply adversarial review (candidate strategy followed by plausible market response); chooses the best robust score, not the most optimistic path.
- Trust calibration: Beta(5,5) prior shrinks win-rate estimates toward 50% until enough resolved paper outcomes exist. It is a historical reliability indicator, not a future win probability.
- Motivation/objective: rewards risk-adjusted net expectancy and penalizes estimated trading costs, adverse volatility and drawdown. It does not reward trade frequency, leverage or revenge trading.

## Safety
The brain may veto/downgrade candidates, never promote a neutral signal. Neural model votes influence decisions only after strict out-of-sample validation. Unknown data remains unknown; missing order book, funding, open interest, social sentiment or real stop-book information is not fabricated.
