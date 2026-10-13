package com.geflipper;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(GeFlipperConfig.GROUP)
public interface GeFlipperConfig extends Config
{
	String GROUP = "geflipper";

	@ConfigSection(name = "Money", description = "Your stack and how much of it to use", position = 0)
	String money = "money";

	@ConfigSection(name = "Shortcuts", description = "Filling in GE offers", position = 1)
	String shortcuts = "shortcuts";

	@ConfigSection(name = "Trade history", description = "Where your trades are saved", position = 2)
	String history = "history";

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
		return 20_000;
	}

	@ConfigItem(keyName = "members", name = "Members items", description = "Include members-only items",
		section = money, position = 4)
	default boolean members()
	{
		return true;
	}

	@ConfigItem(keyName = "quickSetKey", name = "Fill-in key", description = "With the GE price or quantity box open, this key fills in the suggested value (you still press Enter yourself)",
		section = shortcuts, position = 0)
	default Keybind quickSetKey()
	{
		return new Keybind(KeyEvent.VK_E, 0);
	}

	@ConfigItem(keyName = "searchCode", name = "Search code", description = "Type this in the GE item search to list the suggested items",
		section = shortcuts, position = 1)
	default String searchCode()
	{
		return "gf";
	}

	@ConfigItem(keyName = "dataFolder", name = "Data folder", description = "Folder for your trade history (blank: .runelite/ge-flipper in your home folder). For example D:\\GE-Flipper",
		section = history, position = 0)
	default String dataFolder()
	{
		return "";
	}
}
