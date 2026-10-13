package com.geflipper.engine;

import java.util.Map;

/** One scored item offered to {@link Decide#buyPlan}. */
public final class BuyCandidate
{
	public final int id;
	public final String name;
	/** Buy limit left this window; +Infinity when the limit is unknown. */
	public final double limitLeft;
	/** Latest hourly bar (unfilled); may be null. */
	public final Bar bar;
	public final Map<String, Double> features;
	public final Map<String, Double> pred;

	public BuyCandidate(int id, String name, double limitLeft, Bar bar, Map<String, Double> features, Map<String, Double> pred)
	{
		this.id = id;
		this.name = name;
		this.limitLeft = limitLeft;
		this.bar = bar;
		this.features = features;
		this.pred = pred;
	}
}
