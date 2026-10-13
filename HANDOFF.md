# Handoff: where the project stands and what comes next

*Last updated: 2026-10-13, 01:55 UTC.*

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
  - Hourly: 2025-10-12 → today, complete.
  - 5-minute: 2026-08-13 → today, complete.
  - Backed up on the `data` branch.
- **Branch `research/variants`** (not merged yet): strategy variants
  (`lowrisk`, `liquid`, `fastcut`, `patient`), a 5% fill-share run and loss
  diagnostics in `walkforward.py`.

## Validation (backtests) — PASSED, live paper trading pending
- **Year walk-forward round 2** (`research/reports/walkforward-year-r2.*`), 11 two-week folds:
  - `production` (low risk + pump guard + patient offers): +121k/day, 11/11 folds positive, worst fold +14k
  - low risk + guard @5% fill share: +56k/day, 10/11
  - baselines: −187k / −246k per day, 0/11
- **5-minute replay** (`research/reports/replay-5m.*`): Aug 13 → Oct 12 2026, real 5-minute fills, model
  trained only before Aug 13 and never retrained:
  - `production` @10%: +157k/day (+9.5M), 46/60 days positive, 68% win rate, realised max drawdown 254k
  - `production` @5%: +98k/day (+6.0M), 55/60 days positive, realised max drawdown 268k
  - top-3 trades are 3–4% of profit; 4% of exits are cuts; baselines about −31k/day
  - The 4.45M "mark" drawdown at 10% was one thin hour mispricing a holding; realised and
    6-hour-median marks both show about 255k.
- **Live bot:** restarted 23:00 UTC 10-12 with `models/full-year-v1` + `production` preset. Loop:
  `LIVE_DIR=/home/user/osrs-live research/bot_loop.sh` (watcher: `/home/user/osrs-data/watch_bot.sh`).
- **Data backed up** on the `data` branch (1h year + 60 days of 5m).

## Keeping the cloud bot alive
- Start (or restart after a machine restart): `setsid nohup research/watchdog.sh >> /home/user/osrs-data/watchdog.log 2>&1 &`
- The watchdog starts `bot_loop.sh` under `flock` (single instance) and restarts it if `state.json`
  goes stale for 20 minutes. Processes don't survive a machine restart, so the session schedules
  health checks.
- The durable option is the user's PC: `Start GE Flipper.bat` (see README).

## Next steps (in order)
1. **Live paper trading, 3–7 days.** Watch that its results land inside the replay's range
   (+98k to +157k per day on 5.5M). If they don't, recalibrate the fill share.
2. **User trading flow:** first version done on `app/do-this-now`. The Orders tab runs the model
   in the browser and shows buy orders, sell/hold/cut actions for holdings, and tidy-up of offers
   older than 6h, with one-tap logging. It was checked against live prices. Remaining ideas below.
   - Export `full-year-v1` to JSON (`research/export_js.py`) and run it in the app
     (`js/features.js` + `js/gbm.js`; parity is tested).
   - A "Do this now" list sized to the user's stack (11M, 50% reserve), built for sporadic
     check-ins: patient offers that can sit for hours, sell targets that don't need
     repricing, quantities that fill within about 6 hours.
   - One-tap logging of the orders the user places. The RuneLite site's GE export no longer
     exists, so trades are logged manually or via CSV, with our own RuneLite plugin later.
3. Weekly retrain plus a data-collection job.
4. RuneLite companion plugin: auto-logs GE fills and shows orders in-game. No automation.
5. Merge `research/variants` into `main` once the app work lands.

## Baseline results
| strategy | period | profit/day | max DD | win rate | trips |
|---|---|---|---|---|---|
| top_margin | Oct–Jan WF | −372k | 6.0M | — | — |
| v0_1 | Oct–Jan WF | −437k | 6.5M | — | — |
| model | Oct–Jan WF | +104k | 1.84M | — | — |
