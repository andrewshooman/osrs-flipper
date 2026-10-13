# GE Flipper

**An explainable Grand Exchange flipping assistant for Old School RuneScape.**
It shows what to buy, at what price, how many and why. Practise with mock trades
that fill against real market activity, then track your real flips and profit
after tax.

> Advice only. The app never touches the game client. You place every offer
> yourself. Not affiliated with Jagex.

---

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

## Run it (app only)

It's a static web app with **no build step**.

- **Quickest:** open `index.html` in a modern browser.
- **Local server (optional):** `python -m http.server`, then open http://localhost:8000.
- **On a phone:** host it (e.g. GitHub Pages) and use **Add to Home Screen**.

Your browser fetches prices straight from the
[OSRS Wiki real-time prices API](https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices).
Trades, offers and settings are saved in your browser (`localStorage`). Use
**Settings → Export backup** to keep a copy or move to another device.

## How to use it

1. **Practice first.** The app starts in **PRACTICE** mode with an 11M practice
   stack. Tap **Practice buy** on a pick. The mock offer only fills when real
   trades happen at your price, at a realistic share of the volume. When the buy
   completes, **Holdings** tells you where to sell.
2. **Check the model.** **Profit** shows realised profit after tax, win rate, ROI,
   and a daily chart, separately for practice and real.
3. **Go real.** Switch to **REAL**. Place the offer in-game, then tap
   **I placed this** and record fills as they happen. You can also log completed
   trades directly or import a CSV.

Defaults: 11M stack, **50% cash reserve**, at most 25% of free gp in one item,
8 GE slots, 3% stop-loss, 12-hour max hold. All of these are in **Settings**.

## How picks are chosen

Each step is a pure function in `js/model.js`, so every number on screen can
be traced back to its inputs.

1. **Screen the whole market** using the bulk endpoints (`/mapping`, `/latest`,
   `/5m`, `/1h`, and `/24h` when available):
   - Members items are allowed (configurable), and hidden items are skipped.
   - Both sides must have traded recently (20 min for active flips, 90 min overnight).
   - Daily volume must reach a minimum.
   - The post-tax margin must clear your minimum profit per item and minimum ROI.
2. **Quote realistic prices.**
   - **Bid:** one gp above the instant-sell price.
   - **Ask:** one gp below the instant-buy price.
   - Both use the *less favourable* of the latest trade and the recent average,
     so one outlier trade can't create a fake margin.
   - **Tax:** 2% of the sell price, rounded down, capped at 5M per item. Bonds
     are exempt.
3. **Read the history** for the top 40 candidates (`/timeseries`, 5-minute and
   1-hour). From it the model measures:
   - hourly volatility
   - 24-hour and 7-day trend
   - z-score against the 7-day average
   - **margin persistence**: the share of recent 5-minute windows where the
     margin was profitable after tax
   - the typical margin, used to flag one-off spikes
   - volume surges, which can signal news or a dump
4. **Estimate each pick.**
   - **Quantity** = the smallest of:
     - the buy limit left in the current 4-hour window (open offers count toward it)
     - what the market can fill within the strategy's time horizon
     - the capital you can put into that item
   - **Time** = time to buy plus time to sell, assuming you get a modest share
     (8–12%) of each side's volume.
   - **Expected profit** = quantity × margin × reliability (from persistence)
     − risk charge (volatility over the holding time) + trend adjustment
     (falling prices count in full, rising prices only partly).
   - A high-alch floor reduces the risk charge.
5. **Grade** each pick A–D from volume, persistence, data depth, volatility and
   warning flags.
6. **Plan** your free slots and gp.
   - Picks are allocated greedily, ranked by gp per hour divided by the larger
     share of either scarce resource they use: gp or GE slots.
   - Your cash reserve is never touched.

**Holdings advice:**
- **Sell** at market once it's profitable after tax.
- **Stop-loss** when the price drops past your limit.
- **Time stop** once you've held longer than your max hold time.
- **Alch** when high alchemy beats selling.
- **Reprice** a sell offer that has sat above the market.

**Buy-offer advice:**
- **Cancel** when the margin is gone.
- **Raise or cancel** when there are no fills and buyers are paying more.

Strategies:
- **Active flips:** 2-hour horizon, liquid items.
- **Overnight / AFK:** 10-hour horizon, slower items, a heavier risk charge.

## Project layout

```
index.html        app shell
css/styles.css    tokens (dark/light), layout
js/config.js      constants, tax rules, strategy presets, default settings
js/util.js        GE tax, formatting, statistics
js/api.js         OSRS Wiki prices client (cache, concurrency limit)
js/ledger.js      trades, offers, FIFO profit, buy limits, capital & reserve
js/model.js       screen → history → estimate → allocate; advice; practice fill simulator
js/engine.js      orchestration
js/charts.js      canvas price & profit charts
js/app.js         UI
tests/run.js      Node test harness (no dependencies)
```

## Tests

```
npm test   # or: node tests/run.js
```

CI runs the same suite on every push and pull request.

## Roadmap

- **Calibration:** compare each offer's prediction (saved with it) with what
  actually happened, then correct fill-rate and margin estimates per item.
- **Backtesting:** replay the model over historical `/5m?timestamp=` snapshots.
- **More strategies:** dump and dip buys, set and potion-dose arbitrage, high-alch plays.
- Browser notifications for fills, dumps and buy-limit resets.
- Cross-device sync; RuneLite trade import.
