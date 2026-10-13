package com.flipplanner.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.AfterClass;
import org.junit.Test;
import static com.flipplanner.engine.Fixtures.assertExact;
import static com.flipplanner.engine.Fixtures.assertNum;
import static com.flipplanner.engine.Fixtures.num;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Ledger maths against js/ledger.js; the fixture ledgers are read with Gson in the web app's JSON shape. */
public class LedgerParityTest
{
	static final double TOL = 1e-12;
	private static final JsonObject FX = Fixtures.load("ledger.json");

	@AfterClass
	public static void report()
	{
		System.out.println("LedgerParityTest: " + Fixtures.summary());
	}

	static LedgerData ledger(JsonElement e)
	{
		return Fixtures.GSON.fromJson(e, LedgerData.class);
	}

	@Test
	public void gsonKeepsTheWebAppShape()
	{
		for (JsonElement ce : FX.getAsJsonArray("cases"))
		{
			JsonObject want = ce.getAsJsonObject().getAsJsonObject("ledger");
			LedgerData data = ledger(want);
			JsonElement back = Fixtures.GSON.toJsonTree(data);
			assertEquals(want, back);
		}
	}

	@Test
	public void cases()
	{
		int k = 0;
		for (JsonElement ce : FX.getAsJsonArray("cases"))
		{
			JsonObject c = ce.getAsJsonObject();
			String name = "ledger " + k++;
			LedgerData lg = ledger(c.get("ledger"));
			positions(name, c.getAsJsonObject("positions"), Ledger.positions(lg.trades));
			limits(name, c.getAsJsonArray("limits"), lg);

			List<String> open = new ArrayList<>();
			for (Offer o : Ledger.openOffers(lg))
			{
				open.add(o.id);
			}
			List<String> wantOpen = new ArrayList<>();
			for (JsonElement e : c.getAsJsonArray("openOffers"))
			{
				wantOpen.add(e.getAsString());
			}
			assertEquals(name + ": open offers", wantOpen, open);

			JsonObject settings = c.getAsJsonObject("settings");
			Ledger.Capital cap = Ledger.capital(lg, num(settings, "cashReservePct"), settings.get("slots").getAsInt(), num(c, "bankroll"));
			capital(name, c.getAsJsonObject("capital"), cap);

			Ledger.Stats st = Ledger.stats(lg, ZoneOffset.UTC, c.get("now").getAsLong());
			stats(name, c.getAsJsonObject("stats"), st);
		}
	}

	static void capital(String name, JsonObject w, Ledger.Capital cap)
	{
		assertNum(name + ": equity", w.get("equity"), cap.equity, TOL);
		assertNum(name + ": realised", w.get("realised"), cap.realised, TOL);
		assertNum(name + ": holdingCost", w.get("holdingCost"), cap.holdingCost, TOL);
		assertNum(name + ": inBuyOffers", w.get("inBuyOffers"), cap.inBuyOffers, TOL);
		assertNum(name + ": cash", w.get("cash"), cap.cash, TOL);
		assertNum(name + ": reserve", w.get("reserve"), cap.reserve, TOL);
		assertNum(name + ": deployable", w.get("deployable"), cap.deployable, TOL);
		assertEquals(name + ": slotsUsed", w.get("slotsUsed").getAsInt(), cap.slotsUsed);
		assertEquals(name + ": slotsFree", w.get("slotsFree").getAsInt(), cap.slotsFree);
	}

	private static void positions(String name, JsonObject w, Ledger.Positions p)
	{
		JsonArray hs = w.getAsJsonArray("holdings");
		List<Ledger.Holding> got = new ArrayList<>(p.holdings.values());
		assertEquals(name + ": holdings", hs.size(), got.size());
		for (int i = 0; i < hs.size(); i++)
		{
			JsonObject e = hs.get(i).getAsJsonObject();
			Ledger.Holding h = got.get(i);
			String what = name + ": holding " + i;
			assertEquals(what + " itemId", e.get("itemId").getAsInt(), h.itemId);
			assertExact(what + " qty", e.get("qty"), h.qty);
			assertNum(what + " cost", e.get("cost"), h.cost, TOL);
			assertNum(what + " avgCost", e.get("avgCost"), h.avgCost, TOL);
			assertExact(what + " oldestTs", e.get("oldestTs"), h.oldestTs);
			JsonArray lots = e.getAsJsonArray("lots");
			assertEquals(what + " lots", lots.size(), h.lots.size());
			for (int j = 0; j < lots.size(); j++)
			{
				JsonObject l = lots.get(j).getAsJsonObject();
				assertExact(what + " lot qty", l.get("qty"), h.lots.get(j).qty);
				assertExact(what + " lot price", l.get("price"), h.lots.get(j).price);
				assertExact(what + " lot ts", l.get("ts"), h.lots.get(j).ts);
			}
		}
		JsonArray cl = w.getAsJsonArray("closed");
		assertEquals(name + ": closed", cl.size(), p.closed.size());
		for (int i = 0; i < cl.size(); i++)
		{
			JsonObject e = cl.get(i).getAsJsonObject();
			Ledger.ClosedTrip c = p.closed.get(i);
			String what = name + ": closed " + i;
			assertEquals(what + " tradeId", e.get("tradeId").getAsString(), c.tradeId);
			assertEquals(what + " itemId", e.get("itemId").getAsInt(), c.itemId);
			assertExact(what + " qty", e.get("qty"), c.qty);
			assertNum(what + " buyPrice", e.get("buyPrice"), c.buyPrice, TOL);
			assertExact(what + " sellPrice", e.get("sellPrice"), c.sellPrice);
			assertNum(what + " cost", e.get("cost"), c.cost, TOL);
			assertNum(what + " proceeds", e.get("proceeds"), c.proceeds, TOL);
			assertNum(what + " tax", e.get("tax"), c.tax, TOL);
			assertNum(what + " profit", e.get("profit"), c.profit, TOL);
			assertNum(what + " roi", e.get("roi"), c.roi, TOL);
			assertExact(what + " buyTs", e.get("buyTs"), c.buyTs);
			assertExact(what + " sellTs", e.get("sellTs"), c.sellTs);
			assertNum(what + " holdHours", e.get("holdHours"), c.holdHours, TOL);
		}
		JsonArray um = w.getAsJsonArray("unmatched");
		assertEquals(name + ": unmatched", um.size(), p.unmatched.size());
		for (int i = 0; i < um.size(); i++)
		{
			JsonObject e = um.get(i).getAsJsonObject();
			Ledger.Unmatched u = p.unmatched.get(i);
			String what = name + ": unmatched " + i;
			assertEquals(what + " tradeId", e.get("tradeId").getAsString(), u.tradeId);
			assertEquals(what + " itemId", e.get("itemId").getAsInt(), u.itemId);
			assertExact(what + " qty", e.get("qty"), u.qty);
			assertExact(what + " price", e.get("price"), u.price);
			assertExact(what + " ts", e.get("ts"), u.ts);
		}
	}

	private static void limits(String name, JsonArray limits, LedgerData lg)
	{
		for (JsonElement le : limits)
		{
			JsonObject l = le.getAsJsonObject();
			int itemId = l.get("itemId").getAsInt();
			long now = l.get("now").getAsLong();
			String what = name + ": limit " + l;
			Ledger.LimitUsage u = Ledger.buyLimitUsage(lg.trades, itemId, now);
			JsonObject wu = l.getAsJsonObject("usage");
			assertExact(what + " used", wu.get("used"), u.used);
			Fixtures.assertNullable(what + " windowStart", wu.get("windowStart"), u.windowStart, 0);
			Fixtures.assertNullable(what + " resetsAt", wu.get("resetsAt"), u.resetsAt, 0);
			assertExact(what + " remaining", l.get("remaining"), Ledger.remainingLimit(lg, itemId, l.get("limit").getAsInt(), now));
		}
	}

	private static void stats(String name, JsonObject w, Ledger.Stats st)
	{
		assertNum(name + ": profit", w.get("profit"), st.profit, TOL);
		assertNum(name + ": tax", w.get("tax"), st.tax, TOL);
		assertNum(name + ": roi", w.get("roi"), st.roi, TOL);
		assertEquals(name + ": flips", w.get("flips").getAsInt(), st.flips);
		assertNum(name + ": winRate", w.get("winRate"), st.winRate, TOL);
		assertNum(name + ": avgHoldHours", w.get("avgHoldHours"), st.avgHoldHours, TOL);
		assertNum(name + ": today", w.get("today"), st.today, TOL);
		JsonArray bi = w.getAsJsonArray("byItem");
		assertEquals(name + ": byItem", bi.size(), st.byItem.size());
		for (int i = 0; i < bi.size(); i++)
		{
			JsonObject e = bi.get(i).getAsJsonObject();
			Ledger.ItemStats it = st.byItem.get(i);
			String what = name + ": byItem " + i;
			assertEquals(what + " itemId", e.get("itemId").getAsInt(), it.itemId);
			assertNum(what + " profit", e.get("profit"), it.profit, TOL);
			assertExact(what + " qty", e.get("qty"), it.qty);
			assertNum(what + " cost", e.get("cost"), it.cost, TOL);
			assertNum(what + " tax", e.get("tax"), it.tax, TOL);
			assertEquals(what + " flips", e.get("flips").getAsInt(), it.flips);
			assertEquals(what + " wins", e.get("wins").getAsInt(), it.wins);
		}
		JsonArray bd = w.getAsJsonArray("byDay");
		assertEquals(name + ": byDay", bd.size(), st.byDay.size());
		for (int i = 0; i < bd.size(); i++)
		{
			JsonArray e = bd.get(i).getAsJsonArray();
			assertEquals(name + ": day " + i, e.get(0).getAsString(), st.byDay.get(i).day);
			assertNum(name + ": day profit " + i, e.get(1), st.byDay.get(i).profit, TOL);
		}
	}

	@Test
	public void dayKeyUsesTheGivenZone()
	{
		long ts = 1791936000000L; // 2026-10-14 00:00 UTC
		assertEquals("2026-10-14", Ledger.dayKey(ts, ZoneOffset.UTC));
		assertEquals("2026-10-13", Ledger.dayKey(ts, java.time.ZoneId.of("America/New_York")));
	}

	@Test
	public void unknownLimitIsUnbounded()
	{
		assertEquals(Double.POSITIVE_INFINITY, Ledger.remainingLimit(new LedgerData(), 2, 0, 0), 0);
		assertNull(Ledger.buyLimitUsage(new ArrayList<>(), 2, 0).windowStart);
	}
}
