# Live paper-trading state (machine-written)

The GE Flipper bot's mock portfolio. **No real gold is traded.**
- `state.json`: the report the app's **Bot** tab reads
- `data/`: the bot's working state (`live/state.pkl`, hourly market cache, item mapping, current model)

Updated by `.github/workflows/bot.yml` on `main` about every 10 minutes.
This branch is force-pushed as a single commit, so it has no history.
