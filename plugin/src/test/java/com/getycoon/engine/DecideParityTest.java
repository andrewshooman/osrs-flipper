package com.getycoon.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.AfterClass;
import org.junit.Test;
import static com.getycoon.engine.Fixtures.num;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Decide.eligible / buyPlan / holdingAction against js/decide.js. */
public class DecideParityTest
{
	static final double TOL = 1e-12;
	private static final JsonObject FX = Fixtures.load("decide.json");

	@AfterClass
	public static void report()
	{
		System.out.println("DecideParityTest: " + Fixtures.summary());
	}

	@Test
	public void eligible()
	{
		for (JsonElement e : FX.getAsJsonArray("eligible"))
		{
			JsonArray a = e.getAsJsonArray();
			assertEquals(a.get(0).toString(), a.get(1).getAsBoolean(), Decide.eligible(Fixtures.numMap(a.get(0))));
		}
	}

	static BuyCandidate candidate(JsonObject o)
	{
		return new BuyCandidate(o.get("id").getAsInt(), Fixtures.str(o.get("name")), num(o, "limitLeft"), Fixtures.bar(o.get("bar")),
			Fixtures.numMap(o.get("f")), Fixtures.numMap(o.get("pred")));
	}

	@Test
	public void buyPlan()
	{
		int ideas = 0;
		for (JsonElement se : FX.getAsJsonArray("buyPlan"))
		{
			JsonObject s = se.getAsJsonObject();
			String name = s.get("name").getAsString();
			List<BuyCandidate> items = new ArrayList<>();
			for (JsonElement it : s.getAsJsonArray("items"))
			{
				items.add(candidate(it.getAsJsonObject()));
			}
			BuyPlan plan = Decide.buyPlan(items, num(s, "deployable"), s.get("slotsFree").getAsInt(), num(s, "mktRet24h"),
				num(s, "minExpectedGp"));
			if (num(s, "minExpectedGp") == 0)
			{
				BuyPlan four = Decide.buyPlan(items, num(s, "deployable"), s.get("slotsFree").getAsInt(), num(s, "mktRet24h"));
				assertPlan(name + " (4 arguments)", s.getAsJsonObject("out"), four, TOL);
			}
			assertPlan(name, s.getAsJsonObject("out"), plan, TOL);
			ideas += plan.ideas.size();
		}
		assertEquals(true, ideas > 100);
	}

	static void assertPlan(String name, JsonObject out, BuyPlan plan, double tol)
	{
		JsonArray ideas = out.getAsJsonArray("ideas");
		assertEquals(name + ": ideas", ideas.size(), plan.ideas.size());
		for (int i = 0; i < ideas.size(); i++)
		{
			Fixtures.assertIdea(name + ": idea " + i, ideas.get(i).getAsJsonObject(), plan.ideas.get(i), tol);
		}
		JsonArray idx = out.getAsJsonArray("plan");
		assertEquals(name + ": plan", idx.size(), plan.plan.size());
		for (int i = 0; i < idx.size(); i++)
		{
			assertSame(name + ": plan " + i, plan.ideas.get(idx.get(i).getAsInt()), plan.plan.get(i));
		}
		Fixtures.assertNum(name + ": deployUsed", out.get("deployUsed"), plan.deployUsed, tol);
	}

	@Test
	public void holdingAction()
	{
		int i = 0;
		for (JsonElement ce : FX.getAsJsonArray("holding"))
		{
			JsonObject c = ce.getAsJsonObject();
			JsonObject h = c.getAsJsonObject("h");
			HoldingAction a = Decide.holdingAction(num(h, "qty"), num(h, "avgCost"), num(h, "heldHours"), Fixtures.bar(c.get("bar")),
				Fixtures.numMap(c.get("pred")), c.get("itemId").getAsInt());
			Fixtures.assertAction("holding " + i++ + " " + c, c.getAsJsonObject("out"), a);
		}
	}
}
