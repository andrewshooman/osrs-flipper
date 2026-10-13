package com.geflipper.engine;

import java.util.List;

/**
 * A buy order from {@link Decide#buyPlan}. Quantities and amounts are doubles because the
 * JS can carry NaN through them (only with NaN predictions); they are whole numbers otherwise.
 */
public final class BuyIdea
{
	public final int id;
	public final String name;
	/** Bid: hourly instant-sell price + 1. */
	public final long buy;
	public double qty;
	public double cost;
	/** Expected return after tax per gp, fill-weighted. */
	public final double expRet;
	/** Expected gp profit. */
	public double expected;
	/** Predicted hours to buy and sell. */
	public final double hours;
	/** Hourly instant-buy price - 1; NaN when the bar has no high price. */
	public final double sellTarget;
	public final double fill;
	public final double q50;
	public final List<String> reasons;
	/** Instant-sell price from /latest, set by the recommender (null if unknown). */
	public Long liveLow;
	/** Warning when the live price moved away from the hourly close (null if not). */
	public String drift;

	public BuyIdea(int id, String name, long buy, double qty, double cost, double expRet, double expected, double hours,
		double sellTarget, double fill, double q50, List<String> reasons)
	{
		this.id = id;
		this.name = name;
		this.buy = buy;
		this.qty = qty;
		this.cost = cost;
		this.expRet = expRet;
		this.expected = expected;
		this.hours = hours;
		this.sellTarget = sellTarget;
		this.fill = fill;
		this.q50 = q50;
		this.reasons = reasons;
	}

	@Override
	public String toString()
	{
		return "BuyIdea{" + id + " " + name + ": " + Js.toString(qty) + " @ " + buy + ", exp " + Fmt.gp(expected) + "}";
	}
}
