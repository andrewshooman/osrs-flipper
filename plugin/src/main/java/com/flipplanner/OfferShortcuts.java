package com.flipplanner;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.events.GrandExchangeSearched;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.QuantityFormatter;

/**
 * GE helpers. Nothing here types, clicks or confirms anything for the player.
 * - Price or quantity box open: a line in the chatbox shows the suggested
 *   value for the item being set up. The player types it in.
 * - Item search: typing the search code lists the suggested items as results.
 */
@Singleton
public class OfferShortcuts
{
	/** Suggested values, from the latest recommendation. */
	public interface Advice
	{
		/** {price, quantity} to buy this item at, or null. */
		long[] buy(int itemId);

		/** {price, quantity} to sell this item at, or null. */
		long[] sell(int itemId);

		/** Items to list for the search code, best first. */
		List<Integer> searchItems();
	}

	private static final int MODE_INT_INPUT = 7;
	private static final int MODE_LONG_INPUT = 30;
	private static final int TEXT_COLOR = 0xFFB83F;

	private final Client client;
	private final ClientThread clientThread;
	private final FlipPlannerConfig config;
	private Advice advice;

	@Inject
	OfferShortcuts(Client client, ClientThread clientThread, FlipPlannerConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
	}

	public void setAdvice(Advice advice)
	{
		this.advice = advice;
	}

	public void onVarClientIntChanged(VarClientIntChanged e)
	{
		if (e.getIndex() != VarClientID.MESLAYERMODE || !config.showOfferHint())
		{
			return;
		}
		int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
		if ((mode == MODE_INT_INPUT || mode == MODE_LONG_INPUT) && client.getWidget(InterfaceID.GeOffers.SETUP) != null)
		{
			clientThread.invokeLater(this::showHint);
		}
	}

	/** Which box is open: true = price, false = quantity, null = neither. */
	private Boolean priceBox()
	{
		Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (title == null || title.getText() == null)
		{
			return null;
		}
		String t = title.getText();
		if (t.startsWith("Set a price for each item"))
		{
			return true;
		}
		if (t.startsWith("How many do you wish to"))
		{
			return false;
		}
		return null;
	}

	private void showHint()
	{
		Advice a = advice;
		Boolean price = priceBox();
		if (a == null || price == null)
		{
			return;
		}
		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		boolean buying = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0;
		long[] s = buying ? a.buy(itemId) : a.sell(itemId);
		if (s == null)
		{
			return;
		}
		long value = price ? s[0] : s[1];
		if (value <= 0)
		{
			return;
		}
		Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (parent == null)
		{
			return;
		}
		String text = "Flip Planner suggests: " + (buying ? "buy " : "sell ")
			+ (price ? "at " + QuantityFormatter.formatNumber(value) + " gp each" : QuantityFormatter.formatNumber(value));
		Widget hint = parent.createChild(-1, WidgetType.TEXT);
		hint.setText(text);
		hint.setFontId(FontID.PLAIN_12);
		hint.setTextColor(TEXT_COLOR);
		hint.setTextShadowed(true);
		hint.setXTextAlignment(WidgetTextAlignment.CENTER);
		hint.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
		hint.setOriginalX(0);
		hint.setOriginalY(8);
		hint.setWidthMode(WidgetSizeMode.MINUS);
		hint.setOriginalWidth(0);
		hint.setOriginalHeight(16);
		hint.revalidate();
	}

	public void onGrandExchangeSearched(GrandExchangeSearched e)
	{
		Advice a = advice;
		String code = config.searchCode() == null ? "" : config.searchCode().trim();
		if (a == null || code.isEmpty() || e.isConsumed())
		{
			return;
		}
		String input = client.getVarcStrValue(VarClientID.MESLAYERINPUT);
		if (input == null || !input.trim().equalsIgnoreCase(code))
		{
			return;
		}
		List<Integer> ids = a.searchItems();
		if (ids.isEmpty())
		{
			return;
		}
		short[] arr = new short[ids.size()];
		for (int i = 0; i < arr.length; i++)
		{
			arr[i] = (short) (int) ids.get(i);
		}
		client.setGeSearchResultIndex(0);
		client.setGeSearchResultCount(arr.length);
		client.setGeSearchResultIds(arr);
		e.consume();
	}
}
