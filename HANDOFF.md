# Handoff: where the project stands and what comes next

*Last updated: 2026-10-13, 03:00 UTC.*

**Goal:** use the GE tool to make the user coins by **flipping**: buying and
selling on the GE only. Decanting, processing and alching are money-making
methods, not flips, so they're out of scope. The user trades sporadically, a
few check-ins a day, and wants recommendations they can follow plus easy
logging. Nothing places offers automatically, because that gets accounts banned.

## Current state
- **App:** live on GitHub Pages from `main`.
  - Tabs: Bot (default), Orders, Slots, Holdings, Profit, Settings.
  - **Orders** runs the trained model in the browser (`js/features.js`,
    `js/gbm.js`, `js/decide.js`, model in `models/flip-model.js`) with the
    `checkin` preset. It lists:
    - what to do with each holding (list / hold / sell now / cut, 48h max hold)
    - offers to tidy up (older than 6h, or blocking a slot needed for a sell)
    - new buy offers for the slots left over
  - Holdings shows the same model advice as Orders.
  - GE tax uses the wiki's full exemption list (48 items).
- **Live paper-trading bot** (`research/live.py`, `production` preset, model
  `full-year-v1`): started 2026-10-12 23:06 UTC on an 11M mock stack. At 02:49 UTC
  it was +78k (+41k realised, 10 closed trades).
  - State is published to the `live` branch every 5 minutes; the app's Bot tab
    reads it.
- **Research** (`research/`):
  - `collect.py`: downloads snapshots.
  - `panel.py`: builds the time × item price panel.
  - `features.py`: 42 features that use only past data.
  - `train.py`: the LightGBM models.
  - `backtest.py`: GE simulator with tax, buy limits, slots and reserve.
  - `hybrid.py`: hourly decisions with fills on real 5-minute volume.
  - `walkforward.py`: compares model variants plus a 5% fill check.
  - `strategy_model.py`: the strategy and its presets.
  - `live.py` and `bot_loop.sh`: paper-trading bot.
  - `retrain.py`: refresh pipeline (untested).
  - `test_research.py` and `test_parity.py`: tests.

## Validation
- **Year walk-forward**, 11 two-week folds from Dec 2025 to Oct 2026, 11M stack
  with a 50% reserve, fills capped at 10% of real volume.

  | round | change | hourly bot | check-in (Orders) |
  |---|---|---|---|
  | r2 | production preset | +121k/day, 11/11 folds positive | — |
  | r3 | check-in mode (12, 17, 23 UTC), 10% per item | +121k/day | +90k/day, 11/11 folds positive, worst fold +35k |
  | r4 | labels with 6h buy wait | +114k/day | +87k/day, so no gain; kept the 2h labels |
  | r5 | sell priority, sell uplift | see `reports/walkforward-year-r5.*` | |

  Baselines lose in every fold: top-margin −187k/day and the old heuristic
  −246k/day.
- **5-minute replay**, Aug 13 – Oct 12 2026, real 5-minute fills, model never
  retrained:
  - 10% share: +157k/day, realised max drawdown about 255k
  - 5% share: +98k/day

## Running things on the cloud machine
- **Code the bot runs:** `/home/user/osrs-flipper`, kept on `main`. `bot_loop.sh`
  runs `research/live.py` from there every 5 minutes, so a branch switch there
  changes the bot. Develop in the worktree `/home/user/osrs-dev` instead.
- **Bot state and model:** `/home/user/osrs-live`, a checkout of the `live` branch:
  - `data/model`: the model
  - `data/live/state.pkl`: the bot's state
- **Watchdog:**

  ```
  setsid nohup /home/user/osrs-data/watchdog.sh >> /home/user/osrs-data/watchdog.log 2>&1 &
  ```

  It runs `bot_loop.sh` under `flock` and restarts it if `state.json` is stale
  for 20 minutes. Processes don't survive a machine restart. Session health
  checks restart the watchdog.
- **Data:** `/home/user/osrs-data`:
  - 1h snapshots: a year, back to 2025-10-12
  - 5m snapshots: from 2026-08-13
  - backup on the `data` branch
- **Walk-forward model cache:** `research/models/wf-cache/` holds the trained
  fold models. Re-running strategy variants with the same label settings only
  re-simulates (about 10 minutes, versus about 50 when training).
- **Durable option:** the user's PC (`Start GE Flipper.bat`, see the README).

## Next steps
1. **Round 5 decision:** if sell priority and/or sell uplift win, turn them on
   in `PRESETS` (`production`, `checkin`), mirror them in `js/decide.js`, and
   let the bot pick them up from `main`.
2. **Live bot vs. backtest:** after 3–7 days, check that live profit per day
   lands inside the replay range (+98k to +157k on 5.5M deployable). If it
   doesn't, recalibrate the fill share.
3. **Hyperparameter tuning:** `walkforward.py --leaves`, `--lr` and `--rounds`.
   Each new setting retrains all 11 fold models (about 50 minutes, about 11GB of
   RAM). Run only one heavy job at a time.
4. **Test `retrain.py`**, then run it weekly. It updates `research/models/current`
   and `models/flip-model.js`. Deploying to the bot means copying the model into
   `osrs-live/data/model` and pushing `live`.
5. **RuneLite plugin** to log GE fills automatically. This needs network access
   to `repo.runelite.net`.
