# Backtest terminal accounting

The research backtest opens positions at the next candle's open using analysis computed only from earlier candles. Existing positions are checked against each candle's high/low, with stop-loss taking precedence when stop and target are both touched. If an existing position exits during a candle, the engine will not open a replacement at that same candle's open: the exit is only knowable after examining the candle range, so the next eligible entry is the following candle. This prevents a retroactive same-bar re-entry.

If a position is still open after the final historical candle, the backtest marks it out at that candle's close and applies the configured exit slippage, exit fee and estimated funding. The terminal equity-curve point and maximum drawdown now include this end-of-data liquidation. This keeps the reported final balance, equity curve and drawdown internally consistent.

Funding is an estimate rather than historical exchange funding data. Intrabar price ordering is unknown, so stop-first handling is deliberately conservative. Results are research diagnostics, not exchange fills or evidence of future profitability.
