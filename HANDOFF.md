# Handoff: where the project stands and what comes next

*Last updated: 2026-10-13, 22:45 UTC.*

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
  - Orders only suggests buys expected to make at least **10k gp** (Settings → Min
    profit per order; walk-forward r12). Some check-ins have fewer orders than free
    slots, or none.
  - **Trade file on your PC** (Settings): the File System Access API keeps a JSON copy
    of everything in a file the user picks (Chrome/Edge). The app restores from it if
    browser storage is cleared (`js/tradefile.js`).
  - **Import RuneLite** reads the GE Flipper plugin's trade file or a Flipping
    Utilities save.
- **RuneLite plugin "Flip Planner"** (`plugin/`; first test build `plugin-v0.1.0` was
  still called GE Flipper, a name already taken on the Plugin Hub; renamed in 0.1.1):
  - The Java engine (`engine/`) is a bit-identical port of the JS, checked by fixtures
    generated from the JS (`plugin/tools/make-fixtures.js --check` in CI).
  - `OfferTracker` and `TradeStore` keep a permanent per-account trade file in the
    web app's ledger shape.
  - Side panel. GE help: a chatbox line shows the suggested price or quantity, which
    the player types in (the Plugin Hub forbids inserting chatbox text), plus the
    `fp` search code. 0.2.0 is Hub-ready:
    - files go through RuneLite's `Filepath`, in `.runelite/plugin-data/flip-planner`,
      with an Export button
    - it uses the injected Gson and OkHttp, and makes no `Thread.interrupt` calls
    - `internalName = flip-planner`, BSD-2 LICENSE, `icon.png`, `build=standard`
  - It runs through `Launcher` with the player's own RuneLite install (`plugin/dist`
    start scripts) until it's on the Plugin Hub.
  - 0.3.0 added Copilot-style help: one next action at a time (collect, cancel, sell,
    buy, wait) with Skip and Pause, GE highlights and slot timers, notifications,
    a Stats tab, 7-day price graphs, sell-only mode and a block list.
  - 0.3.1: session profit per hour, optional game-chat messages with profit after tax.
  - **Plugin Hub:** standalone repo `andrewshooman/flip-planner` (a copy of `plugin/`
    made by `plugin/tools/sync-standalone.py`); the submission is branch `flip-planner`
    of the user's fork `andrewshooman/plugin-hub`. The user opens the PR to
    runelite/plugin-hub from that branch; pushing the branch updates it.
  - **What's next for the plugin: `ROADMAP.md`.**
  - **Not yet checked in-game.**
  - New releases: run the "plugin release" workflow with a version. Tag pushes are
    refused from the cloud session.
  - GE tax uses the wiki's full exemption list (48 items).
- **Live paper-trading bot** (`research/live.py`, `production` preset, model
  `full-year-v1`): started 2026-10-12 23:06 UTC on an 11M mock stack. Sell priority and
  the 48h max hold have been on since 03:00 UTC on 10-13. At 04:53 UTC it was +101k
  (+76k realised, 55 closed trades). At 08:31 UTC it was +127k (+122k realised,
  121 closed trades).
  - **Since 2026-10-13 ~08:40 UTC it runs on GitHub Actions** (`.github/workflows/bot.yml`).
    It has a 10-minute schedule, which GitHub started firing at 18:14 UTC on
    10-13 (before that, runs were dispatched by hand).
  - **Stalled 17:53–22:45 UTC on 10-13:** round 12 added click counters to `Sim`, and
    the bot's saved state predated them, so every order failed while the runs showed
    green (#38). Since then, a run that hits anything other than a network error fails
    visibly (red). The bot
    loses nothing between runs, but its hourly decisions only happen when a run
    does. The cloud session's container is shut down a few minutes
    after the session goes idle, which stopped the local loop (03:54–04:53 and
    07:36–08:30 UTC). Each run catches up on every 5-minute bucket it missed, so
    late or skipped runs lose nothing. State lives on the `live` branch, which
    the app's Bot tab reads.
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
| r12 | minimum expected profit per buy order (check-in): 0 / 5k / 10k / 20k / 40k; 20k with 15% or 20% per-item cap | — | none +83k (8.6 buys/day, 9.6k per buy), **10k +81k, worst fold +22k: now the default**, 20k +59k (9/11), 40k +2k; bigger caps +61–65k with a 3.2M drawdown. The model already avoids tiny orders; higher bars cost profit, not clicks |

  Baselines lose in every fold: top-margin −187k/day and the old heuristic
  −246k/day.
- **5-minute replay**, Aug 13 – Oct 12 2026, real 5-minute fills, model never
  retrained (`reports/replay-5m-r5.*`):

  | variant | 10% share | 5% share |
  |---|---|---|
  | `production` (sell priority on) | +141k/day, 52/60 days positive, realised max DD 189k | +102k/day, 52/60 days |
  | without sell priority | +157k/day, 46/60 days, max DD 254k | +98k/day, 55/60 days |

  The `checkin` preset (Orders), 3 check-ins a day (`reports/replay-5m-checkin.*`):
  +83k/day at a 10% share (47/60 days positive, realised DD 315k) and +65k/day at
  5% (49/60 days positive, DD 158k).

  The replay and the year walk-forward disagree on sell priority's profit (−10%
  here, +10% over the year). It's kept on for lower risk: more winning days and
  a smaller drawdown at the bot's 10% share, and a worst year fold of +60k
  instead of +14k. The live bot's results will settle it.

  **Re-pricing stale buys (r13 replay, 2026-10-13): no.** At each check-in, an open
  buy older than N hours, under 50% filled, whose item the model still wants, was
  re-placed at the model's current price instead of waiting for the 6h cancel.

  | `checkin` variant | 10% share | 5% share |
  |---|---|---|
  | cancel after 6h (current) | +78.9k/day, 43/60 days, DD 307k | +53.0k/day, 48/60 days, DD 150k |
  | re-price up (N = 1–3h, identical) | +77.2k/day, 44/60 days, DD 278k | +48.4k/day, 43/60 days, DD 140k |
  | re-price up or down (N = 1h) | +79.0k/day, 43/60 days, DD 278k | +45.2k/day, 43/60 days, DD 140k |

  - Re-pricing fires only 0.5–0.9 times a day and saves no clicks.
  - At 10% it's within noise; at 5% it loses 9–15%.
  - So the plugin keeps "cancel after 6h" and gets no "modify offer" action.
  - The baseline is lower than the earlier +83k because it now includes the 10k minimum per order.
  - The experiment code was not merged.

## Running things on the cloud machine
- **The bot is not run here any more** (see above: GitHub Actions). Never run
  `bot_loop.sh` against the `live` branch while the Actions schedule is on, because
  both force-push the state. Deploy code changes to the bot by merging to `main`.
- **Checkouts:** `/home/user/osrs-flipper` stays on `main`; develop in the
  worktree `/home/user/osrs-dev`.
- **Bot state and model:** `/home/user/osrs-live`, a checkout of the `live` branch:
  - `data/model`: the model
  - `data/live/state.pkl`: the bot's state
- **Watchdog (retired):** `/home/user/osrs-data/watchdog.sh` ran `bot_loop.sh`
  under `flock`. It's only useful for running the bot locally with the Actions
  schedule disabled.
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
3. **The cloud container is reclaimed when the session idles**, which kills every
   process. Long jobs (walk-forward, replay) only finish while the session is
   active. A walk-forward resumes from its cached fold models.
4. **Run `retrain.py` weekly** (tested, about 15 minutes). It updates
   `research/models/current` and `models/flip-model.js`. Deploying to the bot
   means copying the model into `osrs-live/data/model` and pushing `live`.
5. **RuneLite plugin:** work through `ROADMAP.md`, top to bottom.
