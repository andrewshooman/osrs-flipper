# Flip Planner: RuneLite plugin

Flip Planner brings the model behind the GE Flipper web app (https://andrewshooman.github.io/osrs-flipper/) into RuneLite:

- **What to buy.** The side panel lists the model's buy orders for your stack: the item, price, quantity, where to sell, and the expected profit. Only orders worth the clicks are shown, so some check-ins have nothing to buy. That's fine.
- **What to do with what you hold.** For each item you hold, the panel says list at a price, hold (with the breakeven price), sell now, or cut.
- **Shortcuts in the GE:**
  - **Search:** type `fp` in the GE item search to list the suggested items.
  - **Price and quantity:** when the price or quantity box is open, a line in the chatbox shows the suggested number. Click it, or press **E**, and the number is filled in. You still press Enter and Confirm yourself.
- **Trade tracking.** Every GE offer you make is recorded with its fills and average prices, including fills that happened while you were logged out. Your profit after tax shows in the panel.
- **Permanent history on your PC.** Trades are saved as you make them to `.runelite/flip-planner/` in your home folder, or to any folder you choose in the plugin's settings (for example `D:\Flip-Planner`). The web app's **Import RuneLite** button reads this file too.

Nothing is automated. Every offer is placed by you, and every shortcut needs your click or key press. This keeps to the same rules as the flipping plugins already on the Plugin Hub.

## Testing it on your RuneLite (before it's on the Plugin Hub)

RuneLite only loads Plugin Hub plugins, unless the client is started outside the Jagex
Launcher. The start script does that with your own RuneLite install: it uses RuneLite's
Java and the client files RuneLite already downloaded, and adds `flip-planner.jar`.
Nothing of RuneLite is bundled. Your settings, other plugins and Plugin Hub plugins all
work as usual. Flip Planner can run next to the Plugin Hub's own "GE Flipper" plugin
(a different plugin) without clashing.

1. **RuneLite installed and opened once.** Install it from runelite.net or the Jagex
   Launcher, open it normally, then close it. This downloads its files into
   `%USERPROFILE%\.runelite\repository2`.
2. **Download** `Flip-Planner-plugin.zip` from the newest *Flip Planner plugin* release
   (https://github.com/andrewshooman/osrs-flipper/releases). Unzip it anywhere, for
   example `D:\Flip-Planner`.
3. **Jagex account? Do this once** (RuneLite's documented method for clients started
   outside the launcher):
   1. Open **RuneLite (configure)** from the Start menu. If it isn't there, run
      `RuneLite.exe --configure`.
   2. In *Client arguments*, add `--insecure-write-credentials` and click **Save**.
   3. Start RuneLite once from the **Jagex Launcher** and log in. This writes
      `%USERPROFILE%\.runelite\credentials.properties`. Then close it.
   4. That file lets anyone who has it log in as you, so never share it. To sign it
      out, delete it, or use *End sessions* in your Jagex account settings.

   Old-style (non-Jagex) accounts skip this and log in normally.
4. **Close any running RuneLite**, then double-click **Start RuneLite with Flip
   Planner.bat**.
   - A black window opens with RuneLite's log, then RuneLite itself.
   - Keep the black window open while you play; closing it closes RuneLite.
   - If something fails, the window stays open: copy the last lines and send them over.
5. **Check the plugin is on.** Open the wrench (Configuration), search *Flip Planner*,
   and set your stack, reserve, slots and minimum profit per order. Its icon (a coin
   with a green arrow) is in the sidebar.

On Mac or Linux, run `start-runelite-flip-planner.sh` instead of the `.bat`.

### What to try, and what to report back
- [ ] The side panel fills in within about 10 seconds of opening it: buy orders, then
      your items, then profit.
- [ ] Type `fp` in the GE item search. Do the suggested items show up as the results?
- [ ] Start a buy for a suggested item and click **Enter price**. Does a line
      *"Flip Planner: click or press E to fill in …"* appear in the chatbox? Does
      clicking it or pressing **E** put the number in the box? (You press Enter yourself.)
- [ ] The same for **Enter quantity**.
- [ ] Place an offer, let it fill (or partly fill), and log out and back in. Is the trade
      in the panel's profit and in
      `%USERPROFILE%\.runelite\flip-planner\account-<number>.json`, counted once?

To build `flip-planner.jar` yourself, you need JDK 11 or newer. Run `./gradlew jar` here, and the jar is in `build/libs/`.

## Development

```
./gradlew build      # compile + unit tests
```

- `src/main/java/com/flipplanner/engine/`: the model, a Java port of the web app's `js/` engine. Tests in `src/test/.../engine/` check it against fixtures generated from the JS (`tools/make-fixtures.js`).
- `tracking/`:
  - `OfferTracker`: GE offer updates in, offers and fills out. It handles the login burst, and offers that changed while you were logged out.
  - `TradeStore`: the trade file and the raw offer log.
- `OfferShortcuts`: the chatbox fill-in line, the fill-in key, and the search code.
- `ui/FlipperPanel`: the side panel.
