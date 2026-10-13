# GE Flipper

**A Grand Exchange trading assistant for Old School RuneScape.** A machine-learning
model picks what to buy, at what price and how many. It then tells you when to sell,
hold or cut losses, and tracks your real trades and profit after tax. A paper-trading
bot runs the same model against the live GE around the clock, so you can watch it
work before you risk real gold.

> Advice only. Nothing touches the game client: you place every offer yourself.
> Not affiliated with Jagex.

**Live app:** https://andrewshooman.github.io/osrs-flipper/

---

## What's in the app

| Tab | What it's for |
| --- | --- |
| **Bot** | The paper-trading bot: mock 11M stack, its open offers, holdings, closed trades and profit. |
| **Orders** | Your to-do list for this check-in: what to do with items you hold, offers to tidy up, and new buy offers sized to your stack. One tap logs each order you place. |
| **Slots** | Your 8 GE slots and the offers in them. |
| **Holdings** | What you own, cost basis after tax, and live advice. |
| **Profit** | Realised profit after tax, win rate, ROI, a daily chart, and how your results compare with what the model predicted. |
| **Settings** | Stack size (default 11M), cash reserve (default 50%), slots, members, backup and restore. |

Prices come straight from the
[OSRS Wiki real-time prices API](https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices).
Trades, offers and settings are saved in your browser (`localStorage`). Use
**Settings → Export backup** to keep a copy or move to another device.

### Using Orders

The plan assumes you check in a few times a day, not every minute.

1. Open **Orders**. It runs the model in your browser on the latest hourly prices.
   It fetches recent history for about 150 items, so give it a moment.
2. **Your items** first: each says *list at X*, *hold* (with the breakeven price),
   *sell now* (the forecast is falling) or *cut* (even the optimistic case won't reach
   breakeven).
3. **Tidy up**: buy offers older than about 6 hours should be cancelled. The edge has
   usually moved by then.
4. **New buy offers**: place them in-game at the listed price and quantity, then tap
   **I placed it**. When it fills, record the fill; Orders then tells you where to sell.
5. The summary shows the expected profit and when to check back.

If a price has moved more than 2% since the model's hourly bar, the order is flagged so
you can double-check it in-game.

## How the model works

- **Data:** a year of hourly snapshots of every item (about 3,400 tradeable items) plus
  60 days of 5-minute snapshots, from the wiki's price history.
- **Features:** 42 per item per hour, built only from past data: margin after tax,
  volumes on each side, momentum over several horizons, volatility, z-score against the
  week, how often the item trades, market-wide moves, and more.
- **Models (LightGBM):**
  - the chance that a buy at instant-sell + 1 fills
  - the return after tax of selling at instant-buy − 1
  - how long the round trip takes
  - 4-hour and 12-hour forward-return quantiles, which drive the hold, sell and cut
    decisions
- **Decisions:**
  - **Sizing:** quarter-Kelly, from expected return vs. forecast spread, at most 10–15%
    of free gp in one item. Your cash reserve is never touched.
  - **Ranking:** expected gp per hour, divided by the larger share of gp or GE slots
    the order uses.
  - **Pump-and-dump guard:** skips items with margins over 15%, a jump of more than 25%
    in 24 hours, or a price 3σ above the week.
  - **Market brake:** halves the size when the whole market falls more than 3% in a day.
- **Tax:** 2% of the sell price, rounded down per item and capped at 5M. The wiki's
  48 exempt items (bonds, low-level food and ammo, teleport tablets, some tools) pay
  none. Buy limits are tracked per 4-hour window.

## Validation

Every test trains only on data before the test period. Fills only happen against real
traded volume, at a capped share (5–10%) of what actually traded.

- **Year walk-forward** (11 two-week folds, Dec 2025 – Oct 2026, 11M stack with a 50% reserve):
  - **Hourly bot strategy:** +121k gp/day, positive in 11/11 folds.
  - **Check-in strategy** (3 check-ins a day, 10% per item; the one Orders uses):
    +90k gp/day, 11/11 folds positive, worst fold +35k/day.
  - **Simple "top margin" flipping baselines:** −187k and −246k gp/day, 0/11 folds
    positive.
- **5-minute replay** (Aug 13 – Oct 12 2026, model never retrained during the test):
  - **10% of real volume:** +157k gp/day, realised max drawdown about 255k.
  - **5% of real volume:** +98k gp/day.
- **Live paper trading:** the Bot tab, running since 2026-10-12.

Backtests are not guarantees. Thin items, game updates and other flippers all move
prices. Start small and compare your Profit tab with the model's predictions.

## Run it on your PC (bot + app, keeps running without any cloud session)

1. Install **Python 3.11+** from https://www.python.org/downloads/. On Windows, tick
   **"Add python.exe to PATH"** during setup.
2. On this repo's GitHub page: **Code → Download ZIP**. Unzip it anywhere, for example
   `D:\GE-Flipper` if you'd rather keep it off the C drive.
3. **Windows:** double-click **`Start GE Flipper.bat`**. **Mac/Linux:** run `./start.sh`.

The first run installs the Python packages and downloads the validated model (about 7 MB).
Then the paper-trading bot trades a mock 11M stack against the live GE every 5 minutes, and
your browser opens **http://localhost:8765**. The **Bot** tab shows this bot, and **Orders**
shows what to buy, sell, hold or cut for your own stack.

Leave the window open to keep it running; close it to stop, and run it again to resume. The
bot's data lives in `bot-data/` inside that folder. No real gold is traded and nothing touches
the game client.

You can also open `index.html` directly, or serve the folder with `python -m http.server`.
There is no build step.

## Project layout

```
index.html            app shell
css/styles.css        tokens (dark/light), layout
js/config.js          constants, tax exemptions, default settings
js/util.js            GE tax, breakeven, formatting, statistics
js/api.js             OSRS Wiki prices client (cache, concurrency limit)
js/ledger.js          trades, offers, FIFO profit, buy limits, capital & reserve
js/features.js        the model's 42 features, computed in the browser
js/gbm.js             LightGBM tree evaluator
js/decide.js          sizing, ranking, guard, hold/sell/cut rules
js/engine.js          Orders pipeline: shortlist → history → predict → plan
js/app.js             UI
models/flip-model.js  the trained model (generated by research/export_js.py)
models/decants.js     potion decanting recipes
research/             data collection, training, backtests, live bot (Python)
tests/run.js          app test suite (no dependencies)
```

Research pipeline (`research/`):
- `collect.py`: downloads snapshots.
- `panel.py` and `features.py`: build the price panel and its features.
- `train.py`: trains the models.
- `backtest.py`, `hybrid.py` and `walkforward.py`: simulators and validation.
- `live.py`: the paper-trading bot.
- `run_bot.py`: the PC runner.
- `retrain.py`: the refresh pipeline.

## Tests

```
npm test                          # app: node tests/run.js
python research/test_research.py  # research pipeline
python research/test_parity.py    # browser features/trees match Python exactly
```

CI runs the app and research suites on every push and pull request.
