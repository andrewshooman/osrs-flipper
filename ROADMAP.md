# Roadmap: what to build next

The goal is still to make the user as much GE profit as possible from flipping, with
as little effort per gp as possible. Flip Planner (the RuneLite plugin) should cover
the most useful parts of Flipping Copilot for free, while staying its own thing:
- plans come from a model tested on a year of prices, built for a few check-ins a day
- everything runs on the player's PC: no account, no server, nothing sent anywhere
- trade history is kept permanently, and the web app can import it

Work the list top to bottom. Each item ends with tests, a PR with green CI, a merge,
and (for plugin changes) a release plus an update of the Plugin Hub submission.
Record results in HANDOFF.md.

## Releasing the plugin
1. Merge the plugin change into `main`.
2. Copy `plugin/` without `tools/` and `dist/` into `andrewshooman/flip-planner`, as one
   commit on `main`.
3. Update `plugins/flip-planner` (the `commit=` line) on branch `flip-planner` of
   `andrewshooman/plugin-hub`. That branch is the user's Plugin Hub pull request, so
   pushing to it updates the submission.
4. Optionally publish a test build: run the *plugin release* workflow with a version.

## Done
- 0.3.1: session profit per hour, game-chat messages with profit after tax on sells.
- 0.4.0: item tooltips (paid, breakeven, now, advice) in the inventory, bank and GE;
  "Price graph" in the right-click menu of tradeable items; "Use detected stack"
  (coins in the inventory and bank, gp in buy offers, items at cost); a GP drop with
  the profit when a sell finishes.
- 0.4.1: fixes from a code review for in-game behaviour (no Leagues/DMM/beta trades
  in the history, safe trade-file loading, slots re-read when turned on, noted items,
  setup price, a responsive panel, the `fp` search alongside the core GE plugin).

## Queue
1. **Re-pricing instead of only cancelling (research first).** In the 5-minute replay
   (`research/hybrid.py`), test: if a buy hasn't filled after N minutes and the live
   instant-sell has moved above our bid, raise it to instant-sell + 1, as long as the
   model's expected return still clears the minimum. Compare with cancel-after-6h. Ship
   it as a MODIFY next action only if it wins.
2. **Opportunity alerts (research first).** Use the 5-minute data to find sharp drops on
   liquid items that revert within hours. Backtest it. Ship as an optional alert only
   if it's profitable after tax in the replay.
3. **Weekly retrain.** Run `research/retrain.py`, export the model to
   `models/flip-model.js` and the plugin's `flip-model.json`, regenerate the parity
   fixtures, and release.
4. **Keep iterating** on whatever the user reports from in-game testing. The GE
   highlights, slot timers, tooltips, the menu option and the GP drop haven't been checked in-game
   yet, so those reports come first.

## Rules to keep (RuneLite Plugin Hub)
- Nothing types or clicks for the player. In particular, no inserting text into the
  chatbox.
- Files only through `Filepath` / `getPluginDirectory()`.
- Use the injected Gson and OkHttp only.
- No reflection, no `Thread.sleep` or `interrupt`, no `Runtime`, no `Desktop`.
- New menu entries must be client-side only. Nothing may send actions to the server.
