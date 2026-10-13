package com.geflipper;

import java.awt.event.KeyEvent;
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
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;
import net.runelite.client.util.QuantityFormatter;

/**
 * GE shortcuts. Every one of them needs a click or key press from the player,
 * and none of them confirms anything: they only save typing.
 * - Price or quantity box open: a line in the chatbox offers the suggested
 *   value; clicking it (or the fill-in key) puts the number in the box.
 * - Item search: typing the search code lists the suggested items.
 */
@Singleton
public class OfferShortcuts implements KeyListener
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
	private static final int HOVER_COLOR = 0xFFFFFF;

	private final Client client;
	private final ClientThread clientThread;
	private final GeFlipperConfig config;
	private Advice advice;
	/** The value the open box would get, or -1 when no box is open. Written on the client thread. */
	private volatile long pending = -1;

	@Inject
	OfferShortcuts(Client client, ClientThread clientThread, GeFlipperConfig config)
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
		if (e.getIndex() != VarClientID.MESLAYERMODE)
		{
			return;
		}
		pending = -1;
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
		String what = price ? QuantityFormatter.formatNumber(value) + " gp each" : QuantityFormatter.formatNumber(value);
		String key = config.quickSetKey() != null && config.quickSetKey().getKeyCode() != KeyEvent.VK_UNDEFINED
			? " or press " + config.quickSetKey() : "";
		Widget hint = parent.createChild(-1, WidgetType.TEXT);
		hint.setText("GE Flipper: click" + key + " to fill in " + what);
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
		hint.setAction(0, "Fill in");
		hint.setHasListener(true);
		hint.setOnOpListener((JavaScriptCallback) ev -> fill(value));
		hint.setOnMouseRepeatListener((JavaScriptCallback) ev -> hint.setTextColor(HOVER_COLOR));
		hint.setOnMouseLeaveListener((JavaScriptCallback) ev -> hint.setTextColor(TEXT_COLOR));
		hint.revalidate();
		pending = value;
	}

	/** Puts the number in the open price/quantity box. The player still presses Enter. */
	private void fill(long value)
	{
		if (priceBox() == null)
		{
			return;
		}
		Widget input = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (input != null)
		{
			input.setText(value + "*");
		}
		client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(value));
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

	@Override
	public void keyPressed(KeyEvent e)
	{
		long v = pending;
		if (v <= 0 || config.quickSetKey() == null || !config.quickSetKey().matches(e))
		{
			return;
		}
		e.consume();
		clientThread.invoke(() -> fill(v));
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		long v = pending;
		if (v > 0 && config.quickSetKey() != null && e.getKeyChar() == Character.toLowerCase((char) config.quickSetKey().getKeyCode()))
		{
			e.consume(); // don't also type the letter into the box
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}
}
