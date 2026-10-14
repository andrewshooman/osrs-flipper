package com.getycoon;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(GeTycoonConfig.GROUP)
public interface GeTycoonConfig extends Config
{
	String GROUP = "getycoon";

	@ConfigSection(name = "Money", description = "Your stack and how much of it to use", position = 0)
	String money = "money";

	@ConfigSection(name = "Grand Exchange", description = "Help while setting up GE offers", position = 1)
	String shortcuts = "shortcuts";

	@ConfigSection(name = "Dip alerts", description = "Alerts when a liquid item suddenly drops well under its 24h average", position = 2)
	String dips = "dips";

	@ConfigItem(keyName = "bankroll", name = "Stack (gp)", description = "Your flipping stack: coins plus what's in offers and items you're flipping. Not used while Use detected stack is on and your bank has been seen.",
		section = money, position = 0)
	@Units(" gp")
	default int bankroll()
	{
		return 11_000_000;
	}

	@ConfigItem(keyName = "useDetectedStack", name = "Use detected stack", description = "Plan with the stack counted in the game: coins and platinum tokens in your inventory and bank, gp in buy offers and items you're flipping. Open your bank once so it can count your coins. Off: use the Stack setting above.",
		section = money, position = 1)
	default boolean useDetectedStack()
	{
		return false;
	}

	@ConfigItem(keyName = "reservePercent", name = "Cash reserve", description = "Share of the stack that is never used",
		section = money, position = 2)
	@Range(max = 90)
	@Units(Units.PERCENT)
	default int reservePercent()
	{
		return 50;
	}

	@ConfigItem(keyName = "slots", name = "GE slots to use", description = "How many GE slots suggestions may use; the rest stay free for your own trades",
		section = money, position = 3)
	@Range(min = 1, max = 8)
	default int slots()
	{
		return 7;
	}

	@ConfigItem(keyName = "minOrderProfit", name = "Min profit per order", description = "Only suggest buys expected to make at least this much after tax. Fewer, bigger orders: small ones aren't worth the clicks.",
		section = money, position = 4)
	@Units(" gp")
	default int minOrderProfit()
	{
		return 10_000;
	}

	@ConfigItem(keyName = "members", name = "Members items", description = "Include members-only items",
		section = money, position = 5)
	default boolean members()
	{
		return true;
	}

	@ConfigItem(keyName = "sellOnly", name = "Sell-only mode", description = "Stop suggesting new buys; only help sell what you hold (e.g. before logging off for a while)",
		section = money, position = 6)
	default boolean sellOnly()
	{
		return false;
	}

	@ConfigItem(keyName = "blockedItems", name = "Never suggest", description = "Items never to suggest buying: names or item ids, comma-separated (right-click a suggestion in the panel to add one)",
		section = money, position = 7)
	default String blockedItems()
	{
		return "";
	}

	@ConfigItem(keyName = "highlightActions", name = "Highlight next action", description = "Outline what to click next in the GE: Collect, a slot's Buy button, the item in the search results, the price/quantity buttons, Confirm",
		section = shortcuts, position = 0)
	default boolean highlightActions()
	{
		return true;
	}

	@ConfigItem(keyName = "slotTimers", name = "Slot timers", description = "Show how long each GE offer has been up, coloured green (fine), amber (getting old), red (cancel it) or blue (collect)",
		section = shortcuts, position = 1)
	default boolean slotTimers()
	{
		return true;
	}

	@ConfigItem(keyName = "notifyOffers", name = "Notify when offers finish", description = "A notification when a buy or sell offer completes",
		section = shortcuts, position = 4)
	default boolean notifyOffers()
	{
		return true;
	}

	@ConfigItem(keyName = "chatMessages", name = "Chat messages", description = "Game-chat messages when an offer finishes, with the profit after tax on sells",
		section = shortcuts, position = 6)
	default boolean chatMessages()
	{
		return true;
	}

	@ConfigItem(keyName = "notifyPlan", name = "Notify about new buys", description = "A notification when a new hourly plan has buys for your free slots",
		section = shortcuts, position = 5)
	default boolean notifyPlan()
	{
		return false;
	}

	@ConfigItem(keyName = "showOfferHint", name = "Show suggested price/quantity", description = "When the GE price or quantity box is open, show the suggested value in the chatbox (you type it in)",
		section = shortcuts, position = 2)
	default boolean showOfferHint()
	{
		return true;
	}

	@ConfigItem(keyName = "searchCode", name = "Search code", description = "Type this in the GE item search to list the suggested items",
		section = shortcuts, position = 3)
	default String searchCode()
	{
		return "gt";
	}

	@ConfigItem(keyName = "itemTooltips", name = "Item tooltips", description = "Hovering an item you're flipping (inventory, bank or GE) shows what you paid, the breakeven and the current advice",
		section = shortcuts, position = 7)
	default boolean itemTooltips()
	{
		return true;
	}

	@ConfigItem(keyName = "priceGraphMenu", name = "Price graph menu option", description = "Adds \"Price graph\" to the right-click menu of items in the inventory, bank and GE",
		section = shortcuts, position = 8)
	default boolean priceGraphMenu()
	{
		return true;
	}

	@ConfigItem(keyName = "gpDrop", name = "GP drop on sells", description = "A floating \"+12.3K gp\" with the profit after tax when a sell finishes, like an XP drop",
		section = shortcuts, position = 9)
	default boolean gpDrop()
	{
		return true;
	}

	@ConfigItem(keyName = "dipAlerts", name = "Dip alerts", description = "While you play, check the newest 5-minute prices every 2 minutes and alert when a liquid item drops well under its 24h average on heavy volume. Act within about 20 minutes or skip it.",
		section = dips, position = 0)
	default boolean dipAlerts()
	{
		return false;
	}

	@ConfigItem(keyName = "dipDrop", name = "Minimum drop", description = "How far under its 24h average an item's instant-sell price must fall (8% was best in testing)",
		section = dips, position = 1)
	@Range(min = 5, max = 30)
	@Units(Units.PERCENT)
	default int dipDrop()
	{
		return 8;
	}

	@ConfigItem(keyName = "skipKey", name = "Skip suggestion key", description = "Skips the suggested buy and shows the next one (same as the Skip button)",
		section = shortcuts, position = 10)
	default Keybind skipKey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(keyName = "graphKey", name = "Price graph key", description = "Opens the price graph of the item in the current suggestion",
		section = shortcuts, position = 11)
	default Keybind graphKey()
	{
		return Keybind.NOT_SET;
	}
}
