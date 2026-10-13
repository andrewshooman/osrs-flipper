package com.geflipper.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static com.geflipper.engine.Fixtures.assertExact;
import static com.geflipper.engine.Fixtures.assertNum;
import static com.geflipper.engine.Fixtures.isNull;
import static com.geflipper.engine.Fixtures.num;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Recommender.run end to end against OSF.engine.doThisNow on the same fake market. */
public class RecommenderParityTest
{
	static final double TOL = 1e-9;
	private static final JsonObject FX = Fixtures.load("recommend.json");
	private static Model model;

	@BeforeClass
	public static void loadModel() throws IOException
	{
		model = Model.loadDefault();
	}

	@AfterClass
	public static void report()
	{
		System.out.println("RecommenderParityTest: " + Fixtures.summary());
	}

	/** The fixture market, served the way WikiPriceSource would. */
	static final class FakeSource implements PriceSource
	{
		final JsonObject scenario;
		final Set<Integer> failing = new HashSet<>();
		final AtomicInteger seriesCalls = new AtomicInteger();

		FakeSource(JsonObject scenario)
		{
			this.scenario = scenario;
			for (JsonElement e : scenario.getAsJsonArray("failing"))
			{
				failing.add(e.getAsInt());
			}
		}

		@Override
		public Map<Integer, ItemMeta> mapping()
		{
			Map<Integer, ItemMeta> m = new HashMap<>();
			for (JsonElement e : FX.getAsJsonArray("mapping"))
			{
				JsonObject o = e.getAsJsonObject();
				int id = o.get("id").getAsInt();
				m.put(id, new ItemMeta(id, o.get("name").getAsString(), o.get("members").getAsBoolean(),
					(int) Fixtures.numOr(o, "limit", 0), (int) Fixtures.numOr(o, "highalch", 0), (int) Fixtures.numOr(o, "value", 0)));
			}
			return m;
		}

		@Override
		public Map<Integer, HourPoint> snapshot1h(long timestampSec) throws IOException
		{
			JsonElement s = scenario.getAsJsonObject("snapshots").get(Long.toString(timestampSec));
			if (s == null)
			{
				throw new IOException("no snapshot " + timestampSec);
			}
			return byId(s);
		}

		@Override
		public Map<Integer, HourPoint> avg24h()
		{
			JsonElement d = scenario.get("avg24h");
			return isNull(d) ? null : byId(d);
		}

		@Override
		public List<HourPoint> timeseries1h(int itemId) throws IOException
		{
			seriesCalls.incrementAndGet();
			if (failing.contains(itemId))
			{
				throw new IOException("HTTP 500");
			}
			JsonElement rows = FX.getAsJsonObject("series").get(Integer.toString(itemId));
			return rows == null ? new ArrayList<>() : Fixtures.points(rows.getAsJsonArray());
		}

		@Override
		public Map<Integer, long[]> latest() throws IOException
		{
			if (scenario.get("latestFails").getAsBoolean())
			{
				throw new IOException("HTTP 503");
			}
			Map<Integer, long[]> m = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : FX.getAsJsonObject("latest").entrySet())
			{
				JsonObject o = e.getValue().getAsJsonObject();
				m.put(Integer.parseInt(e.getKey()), new long[]{isNull(o.get("high")) ? 0 : o.get("high").getAsLong(),
					isNull(o.get("low")) ? 0 : o.get("low").getAsLong()});
			}
			return m;
		}

		private static Map<Integer, HourPoint> byId(JsonElement e)
		{
			// a HashMap on purpose: the Recommender must not rely on the source's order
			Map<Integer, HourPoint> m = new HashMap<>();
			for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet())
			{
				m.put(Integer.parseInt(x.getKey()), Fixtures.snapshotPoint(x.getValue().getAsJsonObject()));
			}
			return m;
		}
	}

	static Recommender.Settings settings(JsonObject o)
	{
		Recommender.Settings s = new Recommender.Settings();
		s.members = o.get("members").getAsBoolean();
		s.bankroll = num(o, "bankroll");
		s.cashReservePct = num(o, "cashReservePct");
		s.slots = o.get("slots").getAsInt();
		s.candidates = o.get("candidates").getAsInt();
		s.minExpectedGp = num(o, "minExpectedGp");
		for (JsonElement e : o.getAsJsonArray("excludedIds"))
		{
			s.excludedIds.add(e.getAsInt());
		}
		return s;
	}

	@Test
	public void sameResultAsDoThisNow() throws IOException
	{
		for (JsonElement se : FX.getAsJsonArray("scenarios"))
		{
			JsonObject sc = se.getAsJsonObject();
			String name = sc.get("name").getAsString();
			FakeSource src = new FakeSource(sc);
			LedgerData ledger = Fixtures.GSON.fromJson(sc.get("ledger"), LedgerData.class);
			List<String> progress = new ArrayList<>();
			Recommender.Result res = new Recommender(model).run(src, ledger, settings(sc.getAsJsonObject("settings")),
				sc.get("now").getAsLong(), t ->
				{
					synchronized (progress)
					{
						progress.add(t);
					}
				});
			JsonObject out = sc.getAsJsonObject("out");
			assertExact(name + ": barTs", out.get("barTs"), res.barTs);
			assertExact(name + ": at", out.get("at"), res.at);
			assertNum(name + ": mkt_ret_1h", out.getAsJsonObject("ctx").get("mkt_ret_1h"), res.ctx.mktRet1h, 1e-12);
			assertNum(name + ": mkt_ret_24h", out.getAsJsonObject("ctx").get("mkt_ret_24h"), res.ctx.mktRet24h, 1e-12);
			LedgerParityTest.capital(name, out.getAsJsonObject("cap"), res.capital);
			assertEquals(name + ": buySlots", out.get("buySlots").getAsInt(), res.buySlots);
			assertEquals(name + ": progress messages", out.get("progress").getAsInt(), progress.size());
			assertTrue(name + ": progress ends with the last item", progress.isEmpty()
				|| progress.get(progress.size() - 1).endsWith("/" + (progress.size() - 1)) || progress.size() == 1);

			JsonArray ideas = out.getAsJsonArray("ideas");
			assertEquals(name + ": ideas", ideas.size(), res.ideas.size());
			for (int i = 0; i < ideas.size(); i++)
			{
				Fixtures.assertIdea(name + ": idea " + i, ideas.get(i).getAsJsonObject(), res.ideas.get(i), TOL);
			}
			JsonArray plan = out.getAsJsonArray("plan");
			assertEquals(name + ": plan", plan.size(), res.plan.size());
			for (int i = 0; i < plan.size(); i++)
			{
				assertTrue(name + ": plan " + i, res.ideas.get(plan.get(i).getAsInt()) == res.plan.get(i));
			}
			assertNum(name + ": deployUsed", out.get("deployUsed"), res.deployUsed, TOL);

			JsonArray holds = out.getAsJsonArray("holds");
			assertEquals(name + ": holds", holds.size(), res.holds.size());
			for (int i = 0; i < holds.size(); i++)
			{
				JsonObject e = holds.get(i).getAsJsonObject();
				Recommender.HoldView h = res.holds.get(i);
				String what = name + ": hold " + i;
				assertEquals(what + " itemId", e.get("itemId").getAsInt(), h.itemId);
				assertEquals(what + " name", Fixtures.str(e.get("name")), h.name);
				assertExact(what + " qty", e.get("qty"), h.qty);
				assertNum(what + " avgCost", e.get("avgCost"), h.avgCost, 1e-12);
				assertNum(what + " cost", e.get("cost"), h.cost, 1e-12);
				assertExact(what + " oldestTs", e.get("oldestTs"), h.oldestTs);
				assertNum(what + " heldHours", e.get("heldHours"), h.heldHours, 1e-12);
				Fixtures.assertAction(what, e.getAsJsonObject("act"), h.action);
				if (isNull(e.get("listing")))
				{
					assertNull(what + " listing", h.listing);
				}
				else
				{
					assertEquals(what + " listing", e.get("listing").getAsString(), h.listing.id);
				}
				Fixtures.assertNullable(what + " ask", e.get("ask"), h.ask, 0);
				Fixtures.assertNullable(what + " pnl", e.get("pnl"), h.pnl, 1e-12);
			}
			List<String> room = new ArrayList<>();
			for (Offer o : res.makeRoom)
			{
				room.add(o.id);
			}
			List<String> wantRoom = new ArrayList<>();
			for (JsonElement e : out.getAsJsonArray("makeRoom"))
			{
				wantRoom.add(e.getAsString());
			}
			assertEquals(name + ": makeRoom", wantRoom, room);
		}
	}

	@Test
	public void oneConcurrentRequestGivesTheSameResult() throws IOException
	{
		JsonObject sc = FX.getAsJsonArray("scenarios").get(2).getAsJsonObject();
		LedgerData ledger = Fixtures.GSON.fromJson(sc.get("ledger"), LedgerData.class);
		Recommender.Result a = new Recommender(model, 1).run(new FakeSource(sc), ledger, settings(sc.getAsJsonObject("settings")),
			sc.get("now").getAsLong(), null);
		JsonArray ideas = sc.getAsJsonObject("out").getAsJsonArray("ideas");
		assertEquals(ideas.size(), a.ideas.size());
		for (int i = 0; i < ideas.size(); i++)
		{
			Fixtures.assertIdea("serial idea " + i, ideas.get(i).getAsJsonObject(), a.ideas.get(i), TOL);
		}
	}
}
