# Handoff: where the project stands and what comes next

*Last updated: 2026-10-13, 05:10 UTC.*

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
  `full-year-v1`): started 2026-10-12 23:06 UTC on an 11M mock stack. Sell priority and
  the 48h max hold have been on since 03:00 UTC on 10-13. At 04:53 UTC it was +101k
  (+76k realised, 55 closed trades). It was down 03:54–04:53 for a machine restart.
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
  - `retrain.py`: refresh pipeline (tested 2026-10-13: about 15 minutes, holdout
    +157k/day on the last 14 days). It updates the app model; the bot's model is
    copied by hand.
  - `test_research.py` and `test_parity.py`: tests.

## Validation
- **Year walk-forward**, 11 two-week folds from Dec 2025 to Oct 2026, 11M stack
  with a 50% reserve, fills capped at 10% of real volume.

  | round | change | hourly bot | check-in (Orders) |
  |---|---|---|---|
  | r2 | production preset | +121k/day, 11/11 folds positive | — |
  | r3 | check-in mode (12, 17, 23 UTC), 10% per item | +121k/day | +90k/day, 11/11 folds positive, worst fold +35k |
  | r4 | labels with 6h buy wait | +114k/day | +87k/day, so no gain; kept the 2h labels |
  | r5 | sell priority (cancel the least-filled buy so a holding can be listed) | **+133k/day, worst fold +60k: now on in `production`** | +90k/day, neutral (Orders keeps selling first) |
| r5 | sell uplift (list above market on a rising forecast) | +132k/day, no gain | +90k/day, no gain |
| r6 | LightGBM 31 leaves, 300 trees | +116k/day, worse | +86k/day, worse |
| r7 | learning rate 0.03, 400 trees (8 of 11 folds) | worse (1,034k vs 1,169k) | worse (784k vs 806k) |
| r8 | item track-record features (7-day fill rate, return per attempt) | +119k/day, worse | +90k/day, same; code kept on branch `research/track-record` |
| r9 | check-in settings: Kelly 0.10/0.25, min return 0.3%, 12h buy wait, 7% cap | — | Kelly and min return change nothing (the 10% cap binds); 12h wait +80k and 7% cap +64k, both worse: keep the preset |
| r10 | check-ins per day (UTC 12/23, +17, +16/20/0, every 3h from 12 to 3) | hourly: +133k (r5) | 2×: +77k, 3×: +90k, 4×: +94k, 6×: +105k/day, all 11/11 folds positive |
| r11 | max hold 24/72h; bot cap 10/20% | 72h +131k, 24h +118k, cap10 +126k, cap20 +115k: keep 48h / 15% | 72h +93k, 24h +87k: keep 48h (within noise) |

  Baselines lose in every fold: top-margin −187k/day and the old heuristic
  −246k/day.
- **5-minute replay**, Aug 13 – Oct 12 2026, real 5-minute fills, model never
  retrained (`reports/replay-5m-r5.*`):

  | variant | 10% share | 5% share |
  |---|---|---|
  | `production` (sell priority on) | +141k/day, 52/60 days positive, realised max DD 189k | +102k/day, 52/60 days |
  | without sell priority | +157k/day, 46/60 days, max DD 254k | +98k/day, 55/60 days |

  The replay and the year walk-forward disagree on sell priority's profit (−10%
  here, +10% over the year). It's kept on for lower risk: more winning days and
  a smaller drawdown at the bot's 10% share, and a worst year fold of +60k
  instead of +14k. The live bot's results will settle it.

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
1. **The model looks well tuned for this data.** Different labels (r4), tree
   settings (r6–r7) and track-record features (r8) all failed to beat it. The
   gains came from strategy (sell priority, r5), so strategy is where to keep
   looking (r9: check-in settings).
2. **Live bot vs. backtest:** after 3–7 days, check that live profit per day
   lands inside the replay range (+102k to +141k on 5.5M deployable). If it
   doesn't, recalibrate the fill share.
3. **Machine restarts kill every process.** The session health check restarts
   the watchdog. A walk-forward resumes from its cached fold models.
4. **Run `retrain.py` weekly** (tested, about 15 minutes). It updates
   `research/models/current` and `models/flip-model.js`. Deploying to the bot
   means copying the model into `osrs-live/data/model` and pushing `live`.
5. **RuneLite plugin** to log GE fills automatically. This needs network access
   to `repo.runelite.net`.
