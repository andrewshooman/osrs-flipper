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

## Queue
1. **Session stats and chat messages.**
   - Profit per hour for the current session in the Stats tab.
   - Optional game-chat messages when offers fill or finish (alongside the tray
     notifications).
2. **Portfolio tooltips.** Hovering an item you're flipping, in the inventory, bank or
   GE, shows what you paid, the breakeven, and the current advice (list / hold / sell / cut).
3. **"Price graph" right-click option** on items in the inventory and GE. The menu entry
   is client-side only: it opens the graph dialog.
4. **Re-pricing instead of only cancelling (research first).** In the 5-minute replay
   (`research/hybrid.py`), test: if a buy hasn't filled after N minutes and the live
   instant-sell has moved above our bid, raise it to instant-sell + 1, as long as the
   model's expected return still clears the minimum. Compare with cancel-after-6h. Ship
   it as a MODIFY next action only if it wins.
5. **Opportunity alerts (research first).** Use the 5-minute data to find sharp drops on
   liquid items that revert within hours. Backtest it. Ship as an optional alert only
   if it's profitable after tax in the replay.
6. **Detect the stack.** Coins in the inventory, plus the bank (cached when opened),
   plus gp in GE offers, plus holdings at cost. Add a setting, "Use detected stack".
7. **Weekly retrain.** Run `research/retrain.py`, export the model to
   `models/flip-model.js` and the plugin's `flip-model.json`, regenerate the parity
   fixtures, and release.
8. **GP drop on finished sells.** A small floating "+12.3K" when a sell completes.
9. **Keep iterating** on whatever the user reports from in-game testing. The GE
   highlights and slot timers haven't been checked in-game yet, so those reports come
   first.

## Rules to keep (RuneLite Plugin Hub)
- Nothing types or clicks for the player. In particular, no inserting text into the
  chatbox.
- Files only through `Filepath` / `getPluginDirectory()`.
- Use the injected Gson and OkHttp only.
- No reflection, no `Thread.sleep` or `interrupt`, no `Runtime`, no `Desktop`.
- New menu entries must be client-side only. Nothing may send actions to the server.
