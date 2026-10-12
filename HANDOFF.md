# Handoff: where the project stands and what comes next

*Last updated: 2026-10-12.*

## Done
- **v0.1 web app** (`index.html`, `js/`): explainable picks, practice and real
  ledgers, slot/holding/offer advice, profit tracking. `npm test` runs 82 checks
  on synthetic data.
- **Research pipeline** (`research/`):
  - `collect.py`: resumable whole-market snapshot collector for `/1h` and `/5m`,
    writing parquet files to `$OSF_DATA` (default `/home/user/osrs-data`).
  - `panel.py`: loads snapshots into dense time × item arrays; also generates
    a synthetic panel for tests.
  - `backtest.py`: event-driven GE simulator. No look-ahead; models tax, buy
    limits, slots, locked gold and the reserve. Includes two baselines:
    `top_margin` (what most tools do) and `v0_1` (the app's current model).
  - `test_research.py`: checks cash conservation, buy limits, no look-ahead
    and the tax rules.

## Blocked on
1. **Network:** this session's sandbox still cannot reach `prices.runescape.wiki`
   or `api.weirdgloop.org`. The setting may only apply to a new session.
2. **Repo:** `andrewshooman/osrs-flipper` doesn't exist or isn't shared with the
   Claude app. Until it is, the code exists only in this container and in the
   zip sent in chat.

## Resume here (in order)
1. Check the API works: `curl -A test https://prices.runescape.wiki/api/v1/osrs/latest`.
   Then confirm the open questions from v0.1: does `/24h` exist, does the API
   allow browser requests (CORS), and how far back does `?timestamp=` history go?
2. Install the research libraries: `pip install pandas pyarrow lightgbm scikit-learn`.
3. Collect data:
   - `python research/collect.py --step 1h --days 365` (about 8.8k requests, ~2.5h)
   - `python research/collect.py --step 5m --days 60` (about 17k requests, ~5h)

   Run both in the background. The container is temporary, so push data
   summaries and models, not raw data.
4. Run the baselines on real data: `python research/backtest.py --step 1h`.
   Record the results below. These are the bar to beat. Also check how
   sensitive they are to `--share` (0.05 / 0.1 / 0.2).
5. Build `research/features.py` and `research/labels.py`. Every feature must
   use only data up to and including time t.
   - **Features:**
     - spread
     - buy/sell volume imbalance
     - volatility over several windows
     - z-scores against 24h and 7d averages
     - mean-reversion half-life (Ornstein–Uhlenbeck fit) and Hurst / variance-ratio
       regime tests
     - hour-of-week
     - item price level and buy limit
     - a market-wide index and its return
   - **Labels:**
     - did a bid at `al+1+k` fill within h hours?
     - forward instant-buy/instant-sell price quantiles at 1, 4, 12 and 24 hours
     - forward net round-trip profit after tax
6. Train with LightGBM:
   - a fill / time-to-fill model
   - quantile price forecasts
   - a regime classifier
   - validate walk-forward by month
7. Build the decision engine as a new strategy:
   - expected value of buy / sell / hold / cut / reprice / cancel / alch
   - bid/ask placement chosen from the fill model
   - quarter-Kelly sizing
   - volatility-based stops, clamped to 2–8%
   - cap of 40% on items that move together
   - market brake when the whole GE index is falling
   - set and potion-dose arbitrage scanner
   - multi-day holds of up to 7 days, at most 30% of deployable gold, only
     where the backtest shows they make money
8. **Gate:** ship only if it beats both baselines on held-out months, measured
   by profit after tax and max drawdown.
9. Ship it:
   - export the models to JSON
   - JS inference in the app
   - a "Do this now" action queue
   - calibration from the user's fills (Thompson sampling)
   - a GitHub Action for daily collection and weekly retraining
10. **Rollout:** about 1 week in practice → review → real with 2M → scale up.

## Baseline results (fill in at step 4)
| strategy | period | profit/day | max DD | win rate | trips |
|---|---|---|---|---|---|
| top_margin | | | | | |
| v0_1 | | | | | |
