# Handoff: where the project stands and what comes next

*Last updated: 2026-10-12, 20:35 UTC.*

**Goal:** use the GE tool to make the user coins. The user trades sporadically, a
few check-ins a day, and wants recommendations they can follow plus easy
logging. Nothing places offers automatically, because that gets accounts banned.

## Current state
- **App:** live on GitHub Pages from `main`.
  - Tabs: Bot (default), Picks (v0.1 heuristic, which **loses** in backtests, so
    use it for practice only), Slots, Holdings, Profit, Settings.
  - GE tax uses the wiki's full exemption list (48 items).
- **Research** (`research/`):
  - `collect.py`: downloads snapshots.
  - `panel.py`: builds the time × item price panel.
  - `features.py`: 42 features that use only past data.
  - `train.py`: the LightGBM models.
  - `backtest.py`: GE simulator with tax, buy limits, slots and reserve.
  - `hybrid.py`: hourly decisions with fills on real 5-minute volume.
  - `walkforward.py`: compares model variants plus a 5% fill check.
  - `live.py` and `bot_loop.sh`: paper-trading bot.
  - `recipes.py`: decanting and processing arbitrage.
  - `test_research.py` and `test_parity.py`: tests.
- **Results so far:**
  - **Walk-forward, Oct 2025 – Jan 2026** (4 folds): model averages +104k/day and
    is positive in 3 of 4 folds. Both baselines are about −400k/day in every fold.
    **Max drawdown was 1.84M**, against a limit of 550k. Fix this next.
  - **First 40-day test:** top_margin −253k/day, v0_1 −399k/day, model +28k/day.
- **Live bot:** the state is on the `live` branch. It was run briefly with an
  Oct–Dec model (`data/model`). Restart it with the full-year model and a reset
  state:

  `LIVE_DIR=<checkout of live> research/bot_loop.sh`

  The GitHub Actions cron was removed; manual dispatch is the fallback only.
- **Data** (on the cloud machine, `/home/user/osrs-data`, gone if the machine is
  reclaimed):
  - Hourly: 2025-10-12 → today, completing about 21:05 UTC on 10-12.
  - 5-minute: 2026-08-13 → today, completing about 00:00 UTC on 10-13.
  - **To do:** back both up to an orphan `data` branch once complete.
- **Branch `research/variants`** (not merged yet): strategy variants
  (`lowrisk`, `liquid`, `fastcut`, `patient`), a 5% fill-share run and loss
  diagnostics in `walkforward.py`.

## Next steps (in order)
1. Year-long walk-forward with the variants:

   `python research/walkforward.py --min-train-days 60 --test-days 14 --every-days 28 --out research/reports/walkforward-year.json`
2. Pick the variant that meets the pre-agreed pass criteria:
   - profitable in most folds
   - beats both baselines
   - still profitable at a 5% fill share
   - max drawdown ≤ ~550k on 5.5M deployable
   - top 3 trades < 50% of profit
   - cuts < 25% of exits
3. Train the full-year model with that variant and put it on `live` at
   `data/model`. Reset the bot state and start `bot_loop.sh`.
4. Run the 5-minute replay (`python research/hybrid.py replay`) and send the
   user a validation report.
5. Back up the data to a `data` branch.
6. **If validation fails:** test more model types and labels side by side.
7. **After 3–7 days of good live results:**
   - an app "Do this now" list for sporadic check-ins
   - logging (the RuneLite website's GE history export no longer exists)
   - our own RuneLite plugin later

## Baseline results
| strategy | period | profit/day | max DD | win rate | trips |
|---|---|---|---|---|---|
| top_margin | Oct–Jan WF | −372k | 6.0M | — | — |
| v0_1 | Oct–Jan WF | −437k | 6.5M | — | — |
| model | Oct–Jan WF | +104k | 1.84M | — | — |
