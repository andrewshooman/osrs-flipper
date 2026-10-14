# GE Tycoon: in-game test checklist

One session of about 30 minutes, with a members account and some coins. It checks each
feature once. You only need to note what's wrong: write down the section number and what
you saw (a screenshot helps).

Start the client as in the README's *Testing it before it's on the Plugin Hub*, using the
newest *GE Tycoon plugin* release. Leave every GE Tycoon setting at its default unless a
step says otherwise.

For the test trade in section 4, pick something cheap that trades a lot, for example
100 feathers or 1,000 air runes. You buy above the price and sell under it so both fill
in seconds. It costs a few hundred gp.

## 1. Start (2 min)
- [ ] The GE Tycoon icon (a coin with a green arrow) is in the sidebar. Open it.
- [ ] Within about 10 seconds the **Plan** tab fills in. It has a next-action card at the
      top, then *Buy*, *Your items* (if you hold anything) and *Profit*. It's fine if
      *Buy* says there's nothing worth buying right now.
- [ ] At the bottom: **Export trades…** and *Free to use · support on Venmo*. Clicking
      the Venmo line opens the Venmo page in your browser.

## 2. Your stack (3 min)
- [ ] Open your bank, then close it. The *Profit* section shows
      *"Detected stack … (coins …, in offers …, in items …)"*. Is the coins number right
      (inventory plus bank, including platinum tokens)?
- [ ] Turn on **Use detected stack** (settings, *Money*). Within a few seconds the
      *Buy* orders are sized for the detected stack. Turn it off again if you prefer the
      number you typed.

## 3. Grand Exchange help (8 min)
At the GE, with at least one empty slot:
- [ ] The next-action card names the step to do now, and the GE outlines it in blue:
      *Collect*, an empty slot's *Buy* button, the item in the search results, the
      price and quantity buttons, then *Confirm*.
- [ ] Type `gt` in the item search. The suggested items show up as the results.
- [ ] Pick one and click **Enter price**. A line *"GE Tycoon suggests: buy at … gp each"*
      shows in the chatbox. The same for **Enter quantity**. Nothing is typed for you.
- [ ] Click **Skip** in the panel. The card suggests a different item.
- [ ] Back out of the offer without placing it (or place it, if you want that flip).
- [ ] Each offer in a slot shows how long it has been up: green, then amber when it's
      getting old, red when it should be cancelled, blue when it needs collecting.

## 4. A test trade (8 min)
Use the cheap item from the top of this page.
- [ ] **Buy:** buy it at a few gp over its price so it fills straight away. You get a
      notification and a chat line *"GE Tycoon: Bought …. Collect it and list it for
      sale."* The slot turns blue. Collect it.
- [ ] **Buy limit:** hover the item in your inventory. The tooltip shows what you paid,
      the breakeven and a line *"Buy limit: … of … left, resets in 3h 59m"*. Start a
      second buy of the same item and open **Enter quantity**: the same buy-limit line
      shows in the chatbox. Back out.
- [ ] **Your items:** the item is listed with *"Worth … · +/-… (…%)"*, green for a
      profit and red for a loss, and a total for everything you hold.
- [ ] **Sell:** sell it a few gp under its price so it fills straight away. You get a
      notification, a chat line *"… for -… gp profit after tax"* and a red *"-… gp"*
      floating at the right of the game view (gold when it's a profit).
- [ ] **Profit:** *Profit* in the panel and the **Stats** tab include the trade.

## 5. Price graphs and tooltips (3 min)
- [ ] Right-click any tradeable item in your inventory or bank. There's a
      **Price graph** option, and it opens a 7-day graph window. Nothing happens in the
      game.
- [ ] Right-click a suggestion in the panel: **Price graph** and **Never suggest**.
- [ ] Set a **Skip suggestion key** and a **Price graph key** (settings,
      *Grand Exchange*). Press each with the game focused. Skip changes the suggestion,
      and the graph key opens the graph. Nothing is typed into the chatbox.

## 6. Stats (2 min)
- [ ] Open the **Stats** tab. It shows today / 7 days / all time, flips, win rate,
      ROI, tax paid, the *Profit over time* line, daily bars, best and worst items, and
      recent flips. Hover the line: a tooltip shows the date and the total.

## 7. Log out and back in (3 min)
- [ ] Place a buy that won't fill right away (a low price), log out, wait a minute and
      log back in. The offer is still tracked and its timer kept counting.
- [ ] Your test trade from section 4 is counted once in *Profit*, not twice. It's also
      in `%USERPROFILE%\.runelite\plugin-data\ge-tycoon\account-<number>.json`.
- [ ] Cancel the low buy.

## 8. Optional, if you have time
- [ ] **Dip alerts:** turn on **Dip alerts** (settings) and leave it on while you play.
      If an alert comes, note whether the item really dipped and whether the suggested
      sell price filled.
- [ ] **Export trades…** saves a copy of your trades wherever you choose (not on C:).
- [ ] **Pause** hides the suggestions and highlights, and **Resume** brings them back.

## What to send back
- The section and line of anything that didn't match, with a screenshot if you can.
- Anything confusing, slow or in the way, even if it worked.
- If the client showed an error or stopped, the last lines of the black window.
