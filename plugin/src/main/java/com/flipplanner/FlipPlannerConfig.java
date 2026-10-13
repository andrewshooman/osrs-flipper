package com.flipplanner;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(FlipPlannerConfig.GROUP)
public interface FlipPlannerConfig extends Config
{
	String GROUP = "flipplanner";

	@ConfigSection(name = "Money", description = "Your stack and how much of it to use", position = 0)
	String money = "money";

	@ConfigSection(name = "Grand Exchange", description = "Help while setting up GE offers", position = 1)
	String shortcuts = "shortcuts";

	@ConfigItem(keyName = "bankroll", name = "Stack (gp)", description = "Your flipping stack: coins plus what's in offers and items you're flipping",
		section = money, position = 0)
	@Units(" gp")
	default int bankroll()
	{
		return 11_000_000;
	}

	@ConfigItem(keyName = "reservePercent", name = "Cash reserve", description = "Share of the stack that is never used",
		section = money, position = 1)
	@Range(max = 90)
	@Units(Units.PERCENT)
	default int reservePercent()
	{
		return 50;
	}

	@ConfigItem(keyName = "slots", name = "GE slots to use", description = "How many GE slots suggestions may use; the rest stay free for your own trades",
		section = money, position = 2)
	@Range(min = 1, max = 8)
	default int slots()
	{
		return 7;
	}

	@ConfigItem(keyName = "minOrderProfit", name = "Min profit per order", description = "Only suggest buys expected to make at least this much after tax. Fewer, bigger orders: small ones aren't worth the clicks.",
		section = money, position = 3)
	@Units(" gp")
	default int minOrderProfit()
	{
		return 10_000;
	}

	@ConfigItem(keyName = "members", name = "Members items", description = "Include members-only items",
		section = money, position = 4)
	default boolean members()
	{
		return true;
	}

	@ConfigItem(keyName = "sellOnly", name = "Sell-only mode", description = "Stop suggesting new buys; only help sell what you hold (e.g. before logging off for a while)",
		section = money, position = 5)
	default boolean sellOnly()
	{
		return false;
	}

	@ConfigItem(keyName = "blockedItems", name = "Never suggest", description = "Items never to suggest buying: names or item ids, comma-separated (right-click a suggestion in the panel to add one)",
		section = money, position = 6)
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
		return "fp";
	}
}
