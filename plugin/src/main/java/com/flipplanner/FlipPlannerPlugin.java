package com.flipplanner;

import com.flipplanner.engine.LedgerData;
import com.flipplanner.engine.Offer;
import com.flipplanner.engine.Trade;
import com.flipplanner.tracking.OfferTracker;
import com.flipplanner.tracking.TradeStore;
import com.flipplanner.ui.FlipperPanel;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.GrandExchangeSearched;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Flip Planner",
	internalName = "flip-planner",
	description = "Model-ranked GE buy suggestions, trade tracking with profit after tax, and one-click price/quantity entry",
	tags = {"grand exchange", "ge", "flipping", "flip", "merching", "profit", "trading"}
)
public class FlipPlannerPlugin extends Plugin
{
	/** What the trade file holds. The ledger has the web app's shape, so the app can import it. */
	static class SaveData
	{
		String app = "flip-planner-plugin";
		int version = 1;
		long savedAt;
		long account;
		String name;
		LedgerData ledger = new LedgerData();
		OfferTracker.SlotState[] slots = new OfferTracker.SlotState[OfferTracker.SLOTS];
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
	private ClientToolbar clientToolbar;
	@Inject
	private FlipPlannerConfig config;
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

	private final Object lock = new Object();
	private ExecutorService worker;
	private FlipperPanel panel;
	private NavigationButton nav;
	private Advisor advisor;
	private ScheduledFuture<?> ticker;
	private ScheduledFuture<?> pendingSave;
	private ScheduledFuture<?> pendingRefresh;

	// Guarded by lock.
	private long account = -1;
	private TradeStore<SaveData> store;
	private SaveData data;
	private OfferTracker tracker;

	@Provides
	FlipPlannerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipPlannerConfig.class);
	}

	@Override
	protected void startUp()
	{
		worker = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "flip-planner");
			t.setDaemon(true);
			return t;
		});
		panel = new FlipperPanel(itemManager, () -> refresh(true), this::exportTrades);
		nav = NavigationButton.builder()
			.tooltip("Flip Planner")
			.icon(ImageUtil.loadImageResource(FlipPlannerPlugin.class, "icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(nav);
		advisor = new Advisor(okHttpClient, gson, config, panel::show);
		shortcuts.setAdvice(advisor);
		// New hourly prices land a few minutes after the hour; check every 5 minutes.
		ticker = scheduler.scheduleWithFixedDelay(() -> refresh(false), 5, 300, TimeUnit.SECONDS);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			loadAccount();
		}
	}

	@Override
	protected void shutDown()
	{
		ticker.cancel(false);
		shortcuts.setAdvice(null);
		clientToolbar.removeNavigation(nav);
		saveNow();
		worker.shutdown();
		synchronized (lock)
		{
			account = -1;
			store = null;
			data = null;
			tracker = null;
		}
	}

	/** .runelite/plugin-data/flip-planner (RuneLite's Filepath API keeps plugins inside their own folder). */
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
				log.warn("Flip Planner: no data folder", e);
				advisor.setError("Couldn't open the plugin's data folder: " + e.getMessage());
				return false;
			}
			TradeStore<SaveData> s = new TradeStore<>(gson, dir, "account-" + Long.toUnsignedString(hash), SaveData.class);
			SaveData d;
			try
			{
				d = s.load();
			}
			catch (IOException e)
			{
				log.warn("Flip Planner: couldn't read {}", s.file(), e);
				advisor.setError(e.getMessage());
				d = null;
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
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			loadAccount();
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		GrandExchangeOffer o = e.getOffer();
		if (o.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			return; // the client clears the slots when logging out or hopping
		}
		if (!loadAccount())
		{
			return;
		}
		long now = System.currentTimeMillis();
		boolean changed;
		synchronized (lock)
		{
			changed = tracker.onOffer(e.getSlot(), o.getItemId(), o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(),
				o.getSpent(), o.getState(), now);
		}
		RawOffer raw = new RawOffer();
		raw.ts = now;
		raw.slot = e.getSlot();
		raw.itemId = o.getItemId();
		raw.price = o.getPrice();
		raw.total = o.getTotalQuantity();
		raw.filled = o.getQuantitySold();
		raw.spent = o.getSpent();
		raw.state = o.getState().name();
		TradeStore<SaveData> s;
		synchronized (lock)
		{
			s = store;
		}
		worker.execute(() ->
		{
			try
			{
				s.append(raw);
			}
			catch (IOException ex)
			{
				log.warn("Flip Planner: couldn't append to the offer log", ex);
			}
		});
		if (changed)
		{
			scheduleSave();
			// Holdings changed: re-plan soon (prices are cached, so this is quick).
			if (pendingRefresh == null || pendingRefresh.isDone())
			{
				pendingRefresh = scheduler.schedule(() -> refresh(true), 3, TimeUnit.SECONDS);
			}
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
			log.warn("Flip Planner: couldn't save the trade file", e);
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
			.setDialogTitle("Export Flip Planner trades")
			.addExtensionFilter("JSON", "json")
			.setDefaultExtension("json")
			.setFileName("flip-planner-trades.json")
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

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged e)
	{
		shortcuts.onVarClientIntChanged(e);
	}

	@Subscribe
	public void onGrandExchangeSearched(GrandExchangeSearched e)
	{
		shortcuts.onGrandExchangeSearched(e);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!FlipPlannerConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		refresh(true);
	}
}
