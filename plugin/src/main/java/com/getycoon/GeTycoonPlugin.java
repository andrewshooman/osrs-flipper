package com.getycoon;

import com.getycoon.engine.BuyIdea;
import com.getycoon.engine.LedgerData;
import com.getycoon.engine.Offer;
import com.getycoon.engine.Recommender;
import com.getycoon.engine.Trade;
import com.getycoon.tracking.OfferTracker;
import com.getycoon.tracking.TradeStore;
import com.getycoon.ui.TycoonPanel;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.GrandExchangeSearched;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.ChatMessageType;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "GE Tycoon",
	internalName = "ge-tycoon",
	description = "GE flipping plans from a tested model: the next action to take, GE highlights, trade tracking and profit after tax",
	tags = {"grand exchange", "ge", "flipping", "flip", "merching", "profit", "trading"}
)
public class GeTycoonPlugin extends Plugin
{
	/** What the trade file holds. The ledger has the web app's shape, so the app can import it. */
	static class SaveData
	{
		String app = "ge-tycoon-plugin";
		int version = 1;
		long savedAt;
		long account;
		String name;
		LedgerData ledger = new LedgerData();
		OfferTracker.SlotState[] slots = new OfferTracker.SlotState[OfferTracker.SLOTS];
		/** Coins and platinum in the bank when it was last open; null if never seen. */
		Long bankCoins;
		long bankSeenAt;
	}

	/** One raw offer update, appended to the offer log. */
	static class RawOffer
	{
		long ts;
		int slot;
		int itemId;
		long price;
		int total;
		int filled;
		long spent;
		String state;
	}

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private GeTycoonConfig config;
	@Inject
	private ItemManager itemManager;
	@Inject
	private OkHttpClient okHttpClient;
	@Inject
	private Gson gson;
	@Inject
	private ScheduledExecutorService scheduler;
	@Inject
	private OfferShortcuts shortcuts;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private GeOverlay overlay;
	@Inject
	private ItemTooltipOverlay tooltipOverlay;
	@Inject
	private GpDropOverlay gpDrop;
	@Inject
	private Notifier notifier;
	@Inject
	private ConfigManager configManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private ChatMessageManager chatMessageManager;

	private final Object lock = new Object();
	private ExecutorService worker;
	/** Side jobs (price graphs, dip checks) run here, so they don't wait behind a model run on {@link #worker}. */
	private ExecutorService side;
	private TycoonPanel panel;
	private NavigationButton nav;
	private Advisor advisor;
	private ScheduledFuture<?> ticker;
	private ScheduledFuture<?> dipTicker;
	/** When each item last raised a dip alert (one per item per 6 hours). */
	private final java.util.Map<Integer, Long> dipAlerted = new ConcurrentHashMap<>();
	/** Dip alerts still worth acting on, newest first; touched only on the side executor. */
	private final java.util.List<Object[]> liveDips = new java.util.ArrayList<>();
	private volatile long lastDipBucket;
	private ScheduledFuture<?> pendingSave;
	private ScheduledFuture<?> pendingRefresh;
	private volatile GeOverlay.State overlayState = new GeOverlay.State();
	private volatile Map<Integer, String> tips = Collections.emptyMap();
	private final BuyLimits buyLimits = new BuyLimits();
	private volatile boolean paused;
	private final Set<Integer> skipped = ConcurrentHashMap.newKeySet();
	private volatile long skippedBar = -1;
	private volatile long loginAt;
	/** Set while logging in or hopping, so the next LOGGED_IN marks a real login (not a region load). */
	private volatile boolean loggingIn = true;
	private volatile long planNotifiedBar = -1;
	private volatile long sessionStart;
	/** Coins and platinum in the inventory; -1 until seen after login. */
	private volatile long invCoins = -1;
	/** invCoins at the last game tick; -1 until seen. Client thread. */
	private long invAtTick = -1;
	/** Coins collected from GE slots to the (closed) bank. Client thread. */
	private final StackDetect.Collects collects = new StackDetect.Collects();

	// Guarded by lock.
	private long account = -1;
	private TradeStore<SaveData> store;
	private SaveData data;
	private OfferTracker tracker;

	@Provides
	GeTycoonConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GeTycoonConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Dip alerts from before the plugin was last turned off belong to the old panel.
		liveDips.clear();
		dipAlerted.clear();
		lastDipBucket = 0;
		worker = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "ge-tycoon");
			t.setDaemon(true);
			return t;
		});
		side = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "ge-tycoon-side");
			t.setDaemon(true);
			return t;
		});
		panel = new TycoonPanel(itemManager, new TycoonPanel.Actions()
		{
			@Override
			public void refresh()
			{
				GeTycoonPlugin.this.refresh(true);
			}

			@Override
			public void export()
			{
				exportTrades();
			}

			@Override
			public void skip()
			{
				skipSuggestion();
			}

			@Override
			public void togglePause()
			{
				paused = !paused;
				updateNext();
			}

			@Override
			public void block(int itemId, String name)
			{
				blockItem(name);
			}

			@Override
			public void graph(int itemId, String name, Double buy, Double sell)
			{
				openGraph(panel, itemId, name, buy, sell);
			}
		});
		nav = NavigationButton.builder()
			.tooltip("GE Tycoon")
			.icon(ImageUtil.loadImageResource(GeTycoonPlugin.class, "icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(nav);
		advisor = new Advisor(okHttpClient, gson, config, panel::show);
		advisor.setOnResult(this::onPlan);
		shortcuts.setAdvice(advisor);
		overlay.setState(() -> overlayState);
		overlayManager.add(overlay);
		tooltipOverlay.setSources(() -> config.itemTooltips() && !paused ? tips : Collections.emptyMap(), () -> overlayState.slots,
			id -> config.itemTooltips() && config.buyLimits() ? buyLimits.line(id, System.currentTimeMillis()) : null);
		advisor.setLimits(id -> config.buyLimits() ? buyLimits.line(id, System.currentTimeMillis()) : null);
		overlayManager.add(tooltipOverlay);
		overlayManager.add(gpDrop);
		keyManager.registerKeyListener(skipHotkey);
		keyManager.registerKeyListener(graphHotkey);
		// New hourly prices land a few minutes after the hour; check every 5 minutes.
		ticker = scheduler.scheduleWithFixedDelay(() -> refresh(false), 5, 300, TimeUnit.SECONDS);
		// The wiki publishes a 5-minute bucket about a minute after it ends: checking every 2 minutes is plenty.
		dipTicker = scheduler.scheduleWithFixedDelay(() -> side.execute(this::checkDips), 30, 120, TimeUnit.SECONDS);
		// Turned on while logged in: RuneLite replays item containers but not GE offers, so read the slots now.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			loggingIn = false;
			loginAt = System.currentTimeMillis();
			sessionStart = loginAt;
			advisor.setSessionStart(sessionStart);
			if (tradeWorld() && loadAccount())
			{
				GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
				for (int i = 0; offers != null && i < offers.length && i < OfferTracker.SLOTS; i++)
				{
					if (offers[i] != null)
					{
						onOffer(i, offers[i], false);
					}
				}
			}
		});
	}

	@Override
	protected void shutDown()
	{
		ticker.cancel(false);
		dipTicker.cancel(false);
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
		}
		if (pendingRefresh != null)
		{
			pendingRefresh.cancel(false);
		}
		overlayManager.remove(overlay);
		overlayManager.remove(tooltipOverlay);
		overlayManager.remove(gpDrop);
		keyManager.unregisterKeyListener(skipHotkey);
		keyManager.unregisterKeyListener(graphHotkey);
		shortcuts.setAdvice(null);
		clientToolbar.removeNavigation(nav);
		saveNow();
		worker.shutdown(); // lets the save finish
		side.shutdownNow(); // price graphs and dip checks: nothing to finish
		overlayState = new GeOverlay.State();
		tips = Collections.emptyMap();
		synchronized (lock)
		{
			account = -1;
			store = null;
			data = null;
			tracker = null;
		}
	}

	/** .runelite/plugin-data/ge-tycoon (RuneLite's Filepath API keeps plugins inside their own folder). */
	private Filepath dataDir() throws IOException
	{
		return getPluginDirectory();
	}

	/** Opens the trade file of the logged-in account (client thread; the file is small). */
	private boolean loadAccount()
	{
		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return false;
		}
		synchronized (lock)
		{
			if (hash == account && tracker != null)
			{
				return true;
			}
			Filepath dir;
			try
			{
				dir = dataDir();
			}
			catch (IOException e)
			{
				log.warn("GE Tycoon: no data folder", e);
				advisor.setError("Couldn't open the plugin's data folder: " + e.getMessage());
				return false;
			}
			TradeStore<SaveData> s = new TradeStore<>(gson, dir, "account-" + Long.toUnsignedString(hash), SaveData.class);
			SaveData d;
			try
			{
				d = s.load();
			}
			catch (TradeStore.DamagedException e)
			{
				log.warn("GE Tycoon: damaged trade file {}", s.file(), e);
				advisor.setError(e.getMessage());
				d = null;
			}
			catch (IOException e)
			{
				// The file is there but unreadable right now (locked, permissions): never start a new one over it.
				// Not setting the store means nothing is saved; the next GE or bank event tries again.
				log.warn("GE Tycoon: couldn't read {}", s.file(), e);
				advisor.setError("Couldn't read your trade file (" + e.getMessage() + "). Trades aren't being saved until it can be read.");
				return false;
			}
			if (d == null)
			{
				d = new SaveData();
				d.account = hash;
			}
			if (d.ledger == null)
			{
				d.ledger = new LedgerData();
			}
			account = hash;
			store = s;
			data = d;
			tracker = new OfferTracker(d.slots, new LedgerSink(d.ledger));
			d.slots = tracker.getSlots();
			advisor.setTradeFile(s.file().toString());
		}
		updateCash(false);
		worker.execute(this::refreshLimits);
		refresh(true);
		return true;
	}

	/** Writes offers and fills into the ledger (called under lock). */
	private static class LedgerSink implements OfferTracker.Sink
	{
		private final LedgerData ledger;

		LedgerSink(LedgerData ledger)
		{
			this.ledger = ledger;
		}

		private Offer find(String id)
		{
			for (int i = ledger.offers.size() - 1; i >= 0; i--)
			{
				if (ledger.offers.get(i).id.equals(id))
				{
					return ledger.offers.get(i);
				}
			}
			return null;
		}

		@Override
		public String openOffer(int slot, int itemId, boolean buy, int qty, long price, long ts)
		{
			Offer o = new Offer(UUID.randomUUID().toString(), itemId, buy ? "buy" : "sell", qty, price, ts);
			o.slot = slot;
			ledger.offers.add(o);
			return o.id;
		}

		@Override
		public void fill(String offerId, int itemId, boolean buy, int qty, double price, long ts)
		{
			Trade t = new Trade(UUID.randomUUID().toString(), itemId, buy ? "buy" : "sell", qty, Math.max(1, Math.round(price)), ts);
			t.offerId = offerId;
			ledger.trades.add(t);
			Offer o = find(offerId);
			if (o != null)
			{
				o.filled += qty;
			}
		}

		@Override
		public void closeOffer(String offerId, boolean anyFilled, long ts)
		{
			Offer o = find(offerId);
			if (o != null && o.isOpen())
			{
				o.status = anyFilled ? Offer.DONE : Offer.CANCELLED;
				o.closedAt = ts;
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState st = e.getGameState();
		if (st == GameState.LOGGED_IN)
		{
			if (loggingIn)
			{
				// A real login or world hop (not a region load): the client now replays every GE slot.
				loggingIn = false;
				loginAt = System.currentTimeMillis();
			}
			if (sessionStart == 0)
			{
				sessionStart = System.currentTimeMillis();
				advisor.setSessionStart(sessionStart);
			}
			if (tradeWorld())
			{
				loadAccount();
			}
		}
		else if (st == GameState.LOGIN_SCREEN || st == GameState.LOGGING_IN || st == GameState.HOPPING
			|| st == GameState.CONNECTION_LOST)
		{
			loggingIn = true;
			loginAt = System.currentTimeMillis();
			if (st == GameState.LOGIN_SCREEN)
			{
				sessionStart = 0; // logging back in starts a new session
				invCoins = -1;
				advisor.setCash(null);
			}
		}
	}

	/**
	 * Leagues, Deadman, tournament, Fresh Start, speedrunning and beta worlds have their own
	 * GE and bank under the same account: their trades must not mix with the main history.
	 */
	private boolean tradeWorld()
	{
		java.util.EnumSet<WorldType> types = client.getWorldType();
		return client.getEnvironment() == 0 && (types == null || (!types.contains(WorldType.SEASONAL)
			&& !types.contains(WorldType.DEADMAN) && !types.contains(WorldType.TOURNAMENT_WORLD)
			&& !types.contains(WorldType.FRESH_START_WORLD) && !types.contains(WorldType.BETA_WORLD)
			&& !types.contains(WorldType.QUEST_SPEEDRUNNING) && !types.contains(WorldType.NOSAVE_MODE)));
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		GrandExchangeOffer o = e.getOffer();
		if (o.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			return; // the client clears the slots when logging out or hopping
		}
		if (!tradeWorld())
		{
			return;
		}
		// The client replays every slot while logging in: only notify about live changes.
		onOffer(e.getSlot(), o, !loggingIn && System.currentTimeMillis() - loginAt > 8000);
	}

	/** Tracks one GE slot update. live: notify if it finished (not part of the login replay). Client thread. */
	private void onOffer(int slot, GrandExchangeOffer o, boolean live)
	{
		if (!loadAccount())
		{
			return;
		}
		long now = System.currentTimeMillis();
		boolean changed;
		TradeStore<SaveData> s;
		synchronized (lock)
		{
			if (tracker == null)
			{
				return; // shut down meanwhile
			}
			long waiting = StackDetect.uncollected(new OfferTracker.SlotState[]{tracker.slot(slot)});
			changed = tracker.onOffer(slot, o.getItemId(), o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(),
				o.getSpent(), o.getState(), now);
			if (live && o.getState() == GrandExchangeOfferState.EMPTY)
			{
				// Collected: the coins went to the inventory or the bank (see onGameTick).
				collects.collected(waiting - StackDetect.uncollected(new OfferTracker.SlotState[]{tracker.slot(slot)}),
					invAtTick, client.getTickCount(), now);
			}
			s = store;
		}
		RawOffer raw = new RawOffer();
		raw.ts = now;
		raw.slot = slot;
		raw.itemId = o.getItemId();
		raw.price = o.getPrice();
		raw.total = o.getTotalQuantity();
		raw.filled = o.getQuantitySold();
		raw.spent = o.getSpent();
		raw.state = o.getState().name();
		worker.execute(() ->
		{
			try
			{
				s.append(raw);
			}
			catch (IOException ex)
			{
				log.warn("GE Tycoon: couldn't append to the offer log", ex);
			}
		});
		if (changed)
		{
			GrandExchangeOfferState st = o.getState();
			boolean done = st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD;
			if (live && done && (config.notifyOffers() || config.chatMessages() || config.gpDrop()))
			{
				String name = advisor.names().getOrDefault(o.getItemId(), itemManager.getItemComposition(o.getItemId()).getName());
				String qty = String.format(java.util.Locale.US, "%,d", o.getQuantitySold());
				Double profit = st == GrandExchangeOfferState.SOLD ? offerProfit(slot) : null;
				String tail = st == GrandExchangeOfferState.BOUGHT ? ". Collect it and list it for sale."
					: profit == null ? "." : String.format(java.util.Locale.US, " for %s%,d gp profit after tax.", profit >= 0 ? "+" : "", Math.round(profit));
				String text = (st == GrandExchangeOfferState.BOUGHT ? "Bought " : "Sold ") + qty + " " + name + tail;
				if (profit != null && config.gpDrop())
				{
					gpDrop.add(profit);
				}
				if (config.notifyOffers())
				{
					notifier.notify("GE Tycoon: " + text);
				}
				if (config.chatMessages())
				{
					chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.GAMEMESSAGE)
						.runeLiteFormattedMessage(new ChatMessageBuilder()
							.append(ChatColorType.HIGHLIGHT).append("GE Tycoon: ")
							.append(ChatColorType.NORMAL).append(text)
							.build())
						.build());
				}
			}
			updateNext();
			scheduleSave();
			updateCash(false); // coins waiting in finished slots count towards the stack
			worker.execute(this::refreshLimits);
			// Holdings changed: re-plan soon (prices are cached, so this is quick).
			if (pendingRefresh == null || pendingRefresh.isDone())
			{
				pendingRefresh = scheduler.schedule(() -> refresh(true), 3, TimeUnit.SECONDS);
			}
		}
	}

	/** Counts coins in the inventory, and in the bank while it's open (remembered per account). */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		int id = e.getContainerId();
		if (id != net.runelite.api.gameval.InventoryID.INV && id != net.runelite.api.gameval.InventoryID.BANK)
		{
			return;
		}
		if (!tradeWorld())
		{
			return;
		}
		long gp = StackDetect.cash(e.getItemContainer().getItems());
		if (id == net.runelite.api.gameval.InventoryID.INV)
		{
			invCoins = gp;
		}
		else
		{
			synchronized (lock)
			{
				if (data == null)
				{
					return;
				}
				data.bankSeenAt = System.currentTimeMillis();
				if (data.bankCoins == null || data.bankCoins != gp)
				{
					data.bankCoins = gp;
					scheduleSave();
				}
			}
		}
		updateCash(true);
	}

	/** Adds coins collected from GE slots to the bank's count when they didn't reach the inventory. */
	@Subscribe
	public void onGameTick(GameTick e)
	{
		long toBank = collects.settle(invCoins, client.getTickCount());
		invAtTick = invCoins;
		if (toBank <= 0)
		{
			return;
		}
		synchronized (lock)
		{
			// A bank update since the collection already has the coins.
			if (data == null || data.bankCoins == null || data.bankSeenAt >= collects.since())
			{
				return;
			}
			data.bankCoins += toBank;
			scheduleSave();
		}
		updateCash(true);
	}

	/** Passes inventory + bank coins to the advisor; re-plans when asked, they moved, and the stack is detected. Client thread. */
	private void updateCash(boolean replan)
	{
		Long bank;
		long waiting;
		synchronized (lock)
		{
			bank = data == null ? null : data.bankCoins;
			waiting = tracker == null ? 0 : StackDetect.uncollected(tracker.getSlots());
		}
		long inv = invCoins;
		Long cash = inv < 0 || bank == null ? null : inv + bank + waiting;
		if (advisor.setCash(cash) && replan && config.useDetectedStack() && (pendingRefresh == null || pendingRefresh.isDone()))
		{
			pendingRefresh = scheduler.schedule(() -> refresh(true), 3, TimeUnit.SECONDS);
		}
	}

	private void scheduleSave()
	{
		if (pendingSave == null || pendingSave.isDone())
		{
			pendingSave = scheduler.schedule(() -> worker.execute(this::saveNow), 1, TimeUnit.SECONDS);
		}
	}

	private void saveNow()
	{
		TradeStore<SaveData> s;
		String json;
		synchronized (lock)
		{
			if (store == null || data == null)
			{
				return;
			}
			data.savedAt = System.currentTimeMillis();
			s = store;
			json = gson.toJson(data);
		}
		try
		{
			s.save(gson.fromJson(json, SaveData.class));
		}
		catch (IOException e)
		{
			log.warn("GE Tycoon: couldn't save the trade file", e);
			advisor.setError("Couldn't save your trades: " + e.getMessage());
		}
	}

	/** Panel button: writes a copy of the trade history wherever the player chooses (Swing thread). */
	private void exportTrades()
	{
		String json;
		TradeStore<SaveData> s;
		synchronized (lock)
		{
			if (data == null || store == null)
			{
				advisor.setError("Log in first: trades are saved per account.");
				return;
			}
			json = gson.toJson(data);
			s = store;
		}
		java.util.List<Filepath> picked = new Filepath.Chooser()
			.setIsSave()
			.setAcceptsFiles()
			.setDialogTitle("Export GE Tycoon trades")
			.addExtensionFilter("JSON", "json")
			.setDefaultExtension("json")
			.setFileName("ge-tycoon-trades.json")
			.showDialog(panel);
		if (picked == null || picked.isEmpty())
		{
			return;
		}
		Filepath target = picked.get(0);
		SaveData copy = gson.fromJson(json, SaveData.class);
		worker.execute(() ->
		{
			try
			{
				s.exportTo(target, copy);
			}
			catch (IOException ex)
			{
				advisor.setError("Couldn't export: " + ex.getMessage());
			}
		});
	}

	/** Re-runs the model. force = now; otherwise only when a newer hourly bar is out. */
	private void refresh(boolean force)
	{
		LedgerData snapshot;
		synchronized (lock)
		{
			if (data == null)
			{
				return;
			}
			snapshot = gson.fromJson(gson.toJson(data.ledger), LedgerData.class);
		}
		worker.execute(() -> advisor.run(snapshot, force));
	}

	/** Profit after tax of the sell offer that just finished in a slot (FIFO against your buys); null if unknown. */
	private Double offerProfit(int slot)
	{
		synchronized (lock)
		{
			if (tracker == null || data == null || tracker.slot(slot) == null)
			{
				return null;
			}
			String offerId = tracker.slot(slot).offerId;
			java.util.Set<String> ids = new java.util.HashSet<>();
			for (Trade t : data.ledger.trades)
			{
				if (offerId.equals(t.offerId))
				{
					ids.add(t.id);
				}
			}
			double sum = 0;
			boolean any = false;
			for (com.getycoon.engine.Ledger.ClosedTrip c : com.getycoon.engine.Ledger.positions(data.ledger.trades).closed)
			{
				if (ids.contains(c.tradeId))
				{
					sum += c.profit;
					any = true;
				}
			}
			return any ? sum : null;
		}
	}

	/** Worker thread, after each model run. */
	private void onPlan()
	{
		Recommender.Result r = advisor.lastResult();
		tips = ItemTips.build(r);
		if (r != null && r.barTs != skippedBar)
		{
			skipped.clear();
			skippedBar = r.barTs;
		}
		NextAction a = updateNext();
		if (r != null && a != null && a.type == NextAction.Type.BUY && config.notifyPlan() && !paused && r.barTs > planNotifiedBar
			&& System.currentTimeMillis() - loginAt > 8000)
		{
			planNotifiedBar = r.barTs;
			notifier.notify("GE Tycoon: new buys to place. Next: " + a.title + " " + a.detail);
		}
	}

	/** Recomputes the next action from the tracked slots and the latest plan. Any thread. */
	private NextAction updateNext()
	{
		NextAction.Input in = new NextAction.Input();
		in.now = System.currentTimeMillis();
		in.sellOnly = config.sellOnly();
		in.maxSlots = config.slots();
		in.skipped = skipped;
		in.buyWaitHours = com.getycoon.engine.Decide.P.BUY_WAIT_HOURS;
		in.nextPlanAt = (in.now / 3_600_000L + 1) * 3_600_000L + 5 * 60_000L;
		synchronized (lock)
		{
			if (tracker == null || data == null)
			{
				return null;
			}
			OfferTracker.SlotState[] src = tracker.getSlots();
			for (int i = 0; i < src.length; i++)
			{
				OfferTracker.SlotState s = src[i];
				in.slots[i] = s;
				if (s != null)
				{
					for (Offer o : data.ledger.offers)
					{
						if (o.id.equals(s.offerId))
						{
							in.placedAt[i] = o.placedAt;
							break;
						}
					}
				}
			}
		}
		Recommender.Result r = advisor.lastResult();
		if (r != null)
		{
			for (BuyIdea b : r.plan)
			{
				in.buys.add(new NextAction.Buy(b.id, b.name, b.buy, (long) b.qty, b.expected));
			}
			for (Recommender.HoldView h : r.holds)
			{
				long price = h.action.price == null ? 0 : Math.round(h.action.price);
				in.holds.add(new NextAction.Hold(h.itemId, h.name != null ? h.name : "item " + h.itemId, h.qty, h.action.action, price, h.listing != null));
			}
			for (Offer o : r.makeRoom)
			{
				in.makeRoom.add(o.id);
			}
		}
		NextAction a = NextAction.pick(in);
		GeOverlay.State st = new GeOverlay.State();
		st.action = paused || r == null ? null : a;
		st.slots = in.slots;
		st.placedAt = in.placedAt;
		st.buyWaitHours = in.buyWaitHours;
		st.highlight = config.highlightActions() && !paused;
		st.timers = config.slotTimers();
		overlayState = st;
		boolean p = paused;
		SwingUtilities.invokeLater(() ->
		{
			if (p)
			{
				panel.showNext("Paused", "Suggestions and GE highlights are off. Your trades are still tracked.", false, true);
			}
			else if (r == null)
			{
				panel.showNext("Waiting for prices…", "The first plan takes about 10 seconds after you log in.", false, false);
			}
			else
			{
				panel.showNext(a.title, a.detail, a.type == NextAction.Type.BUY, false);
			}
		});
		return a;
	}

	/** Recomputes the buy-limit windows from the recorded trades. Worker thread (the item list may need fetching). */
	private void refreshLimits()
	{
		java.util.List<Trade> trades;
		synchronized (lock)
		{
			if (data == null)
			{
				return;
			}
			trades = new java.util.ArrayList<>(data.ledger.trades);
		}
		java.util.Map<Integer, com.getycoon.engine.ItemMeta> meta;
		try
		{
			meta = advisor.prices().mapping();
		}
		catch (IOException e)
		{
			meta = Collections.emptyMap();
		}
		buyLimits.update(trades, meta, System.currentTimeMillis());
	}

	/** Skips the suggested buy (button or key): the next action moves on to another item. */
	private void skipSuggestion()
	{
		NextAction a = overlayState.action;
		if (a != null && a.type == NextAction.Type.BUY)
		{
			skipped.add(a.itemId);
			updateNext();
		}
	}

	// Keybinds only trigger the plugin's own actions; nothing is typed or sent to the game.
	private final HotkeyListener skipHotkey = new HotkeyListener(() -> config.skipKey())
	{
		@Override
		public void hotkeyPressed()
		{
			skipSuggestion();
		}
	};

	private final HotkeyListener graphHotkey = new HotkeyListener(() -> config.graphKey())
	{
		@Override
		public void hotkeyPressed()
		{
			NextAction a = overlayState.action;
			if (a != null && a.itemId > 0)
			{
				Double[] marks = graphMarks(a.itemId);
				String name = a.name != null ? a.name : advisor.names().getOrDefault(a.itemId, "Item " + a.itemId);
				openGraph(client.getCanvas(), a.itemId, name, marks[0], marks[1]);
			}
		}
	};

	/** Adds an item to the "Never suggest" setting and re-plans. Swing thread. */
	private void blockItem(String name)
	{
		String cur = config.blockedItems() == null ? "" : config.blockedItems().trim();
		configManager.setConfiguration(GeTycoonConfig.GROUP, "blockedItems", cur.isEmpty() ? name : cur + ", " + name);
		refresh(true);
	}

	/** How long a dip alert stays in the panel: acting later than this didn't pay in testing. */
	private static final long DIP_SHOW_MS = 30 * 60_000L;

	/** Looks for dips in the newest 5-minute prices and alerts about the best two. Side executor. */
	private void checkDips()
	{
		long now = System.currentTimeMillis();
		if (!config.dipAlerts() || paused || client.getGameState() != GameState.LOGGED_IN)
		{
			publishDips(now, !config.dipAlerts(), false);
			return;
		}
		try
		{
			com.getycoon.engine.WikiPriceSource src = advisor.prices();
			java.util.Map<Integer, com.getycoon.engine.HourPoint> five = src.latest5m();
			long bucket = five.isEmpty() ? 0 : five.values().iterator().next().timestamp;
			if (bucket == 0 || bucket == lastDipBucket)
			{
				publishDips(now, false, false);
				return;
			}
			lastDipBucket = bucket;
			dipAlerted.values().removeIf(t -> now - t > DipAlerts.COOLDOWN_MS);
			Set<Integer> skip = new java.util.HashSet<>(advisor.blocked());
			skip.addAll(dipAlerted.keySet());
			Recommender.Result r = advisor.lastResult();
			// Free gp: above the reserve and not in offers or items (before the first plan, the stack less the reserve).
			double free = r != null ? r.capital.deployable : config.bankroll() * (1 - config.reservePercent() / 100.0);
			LedgerData ledger;
			synchronized (lock)
			{
				ledger = data == null ? null : gson.fromJson(gson.toJson(data.ledger), LedgerData.class);
			}
			java.util.List<DipAlerts.Alert> found = DipAlerts.find(five, src.avg24h(), src.mapping(), skip, config.members(),
				config.dipDrop() / 100.0, free, (id, limit) -> ledger == null ? (limit > 0 ? limit : Double.POSITIVE_INFINITY)
					: com.getycoon.engine.Ledger.remainingLimit(ledger, id, limit, now));
			for (DipAlerts.Alert a : found.subList(0, Math.min(2, found.size())))
			{
				dipAlerted.put(a.itemId, now);
				liveDips.add(0, new Object[]{a, now});
				notifier.notify("GE Tycoon dip: " + a.text());
				if (config.chatMessages())
				{
					chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.GAMEMESSAGE)
						.runeLiteFormattedMessage(new ChatMessageBuilder()
							.append(ChatColorType.HIGHLIGHT).append("GE Tycoon dip: ")
							.append(ChatColorType.NORMAL).append(a.text())
							.build())
						.build());
				}
			}
			publishDips(now, false, !found.isEmpty());
		}
		catch (IOException e)
		{
			log.debug("GE Tycoon: dip check failed", e);
		}
	}

	/** Drops dip alerts older than {@link #DIP_SHOW_MS} (or all of them) and shows the rest if anything changed. Side executor. */
	private void publishDips(long now, boolean clear, boolean added)
	{
		int before = liveDips.size();
		liveDips.removeIf(d -> clear || now - (long) d[1] > DIP_SHOW_MS);
		if (!added && liveDips.size() == before)
		{
			return;
		}
		java.util.Map<Integer, long[]> buys = new java.util.LinkedHashMap<>(); // newest first, for the search code
		java.util.List<String> lines = new java.util.ArrayList<>();
		java.time.format.DateTimeFormatter hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
		for (Object[] d : liveDips)
		{
			DipAlerts.Alert a = (DipAlerts.Alert) d[0];
			buys.putIfAbsent(a.itemId, new long[]{a.price, a.qty});
			lines.add(java.time.Instant.ofEpochMilli((long) d[1]).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(hm)
				+ " " + a.text());
		}
		advisor.setDips(buys, lines);
	}

	/** Opens the price graph for an item (history is fetched on its own thread). */
	private void openGraph(java.awt.Component parent, int itemId, String name, Double buy, Double sell)
	{
		side.execute(() ->
		{
			try
			{
				java.util.List<com.getycoon.engine.HourPoint> pts = advisor.prices().timeseries1h(itemId);
				SwingUtilities.invokeLater(() -> com.getycoon.ui.PriceGraphDialog.open(parent, name, pts, buy, sell));
			}
			catch (IOException ex)
			{
				advisor.setError("Couldn't load the price history: " + ex.getMessage());
			}
		});
	}

	/** Adds "Price graph" to the right-click menu of items in the inventory, bank and GE. Client-side only. */
	@Subscribe
	public void onMenuOpened(MenuOpened e)
	{
		if (!config.priceGraphMenu())
		{
			return;
		}
		MenuEntry[] entries = e.getMenuEntries();
		int id = -1;
		for (int i = entries.length - 1; i >= 0 && id <= 0; i--)
		{
			id = ItemTooltipOverlay.itemUnder(entries[i], overlayState.slots);
		}
		if (id <= 0)
		{
			return;
		}
		int itemId = itemManager.canonicalize(id);
		net.runelite.api.ItemComposition item = itemManager.getItemComposition(itemId);
		if (!item.isGeTradeable())
		{
			return;
		}
		String name = item.getName();
		Double[] marks = graphMarks(itemId);
		client.getMenu().createMenuEntry(1)
			.setOption("Price graph")
			.setTarget("<col=ff9040>" + name + "</col>")
			.setType(MenuAction.RUNELITE)
			.onClick(m -> openGraph(client.getCanvas(), itemId, name, marks[0], marks[1]));
	}

	/** Suggested buy and sell prices to mark on an item's graph, from the latest plan; nulls when there's none. */
	private Double[] graphMarks(int itemId)
	{
		Recommender.Result r = advisor.lastResult();
		Double buy = null;
		Double sell = null;
		if (r != null)
		{
			for (BuyIdea b : r.plan)
			{
				if (b.id == itemId)
				{
					buy = (double) b.buy;
					sell = b.sellTarget;
				}
			}
			for (Recommender.HoldView h : r.holds)
			{
				if (h.itemId == itemId && h.action.price != null && Double.isFinite(h.action.price) && h.action.price > 0)
				{
					sell = h.action.price;
				}
			}
		}
		return new Double[]{buy, sell};
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged e)
	{
		shortcuts.onVarClientIntChanged(e);
	}

	// Before the core GE plugin, whose fuzzy search consumes every non-empty search.
	@Subscribe(priority = 1)
	public void onGrandExchangeSearched(GrandExchangeSearched e)
	{
		shortcuts.onGrandExchangeSearched(e);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!GeTycoonConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		refresh(true);
		updateNext();
	}
}
