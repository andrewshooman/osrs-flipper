# GE Flipper: RuneLite plugin

The GE Flipper model inside RuneLite:

- **What to buy.** The side panel lists the model's buy orders for your stack: the item, price, quantity, where to sell, and the expected profit. Only orders worth the clicks are shown, so some check-ins have nothing to buy. That's fine.
- **What to do with what you hold.** For each item you hold, the panel says list at a price, hold (with the breakeven price), sell now, or cut.
- **Shortcuts in the GE:**
  - **Search:** type `gf` in the GE item search to list the suggested items.
  - **Price and quantity:** when the price or quantity box is open, a line in the chatbox shows the suggested number. Click it, or press **E**, and the number is filled in. You still press Enter and Confirm yourself.
- **Trade tracking.** Every GE offer you make is recorded with its fills and average prices, including fills that happened while you were logged out. Your profit after tax shows in the panel.
- **Permanent history on your PC.** Trades are saved as you make them to `.runelite/ge-flipper/` in your home folder, or to any folder you choose in the plugin's settings (for example `D:\GE-Flipper`). The web app's **Import RuneLite** button reads this file too.

Nothing is automated. Every offer is placed by you, and every shortcut needs your click or key press. This keeps to the same rules as the flipping plugins already on the Plugin Hub.

## Running it before it's on the Plugin Hub

RuneLite only loads Plugin Hub plugins, unless the client is started outside the Jagex Launcher. The start script does that with your own RuneLite install: it uses RuneLite's Java and the client files RuneLite already downloaded, and adds `ge-flipper.jar`.

1. Install RuneLite from runelite.net and open it normally once.
2. Put `ge-flipper.jar` and `Start RuneLite with GE Flipper.bat` in one folder. The `.bat` is in `plugin/dist/`; on Mac or Linux use `start-runelite-ge-flipper.sh`.
3. **Jagex accounts only:** let RuneLite remember your login (RuneLite's documented method for clients started this way):
   1. In the Start menu, open **RuneLite (configure)**. In *Client arguments*, add `--insecure-write-credentials` and click Save.
   2. Start RuneLite once from the **Jagex Launcher**. This writes `.runelite/credentials.properties`.
   3. That file lets anyone who has it log in as you. Never share it. To sign it out, delete it, or use *End sessions* in your Jagex account settings.
4. Double-click **Start RuneLite with GE Flipper.bat**. GE Flipper's icon appears in the sidebar.

To build `ge-flipper.jar` yourself, you need JDK 11 or newer. Run `./gradlew jar` here, and the jar is in `build/libs/`.

## Development

```
./gradlew build      # compile + unit tests
```

- `src/main/java/com/geflipper/engine/`: the model, a Java port of the web app's `js/` engine. Tests in `src/test/.../engine/` check it against fixtures generated from the JS (`tools/make-fixtures.js`).
- `tracking/`:
  - `OfferTracker`: GE offer updates in, offers and fills out. It handles the login burst, and offers that changed while you were logged out.
  - `TradeStore`: the trade file and the raw offer log.
- `OfferShortcuts`: the chatbox fill-in line, the fill-in key, and the search code.
- `ui/FlipperPanel`: the side panel.
