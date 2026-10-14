package com.getycoon.engine;

import java.util.List;

/** Result of {@link Decide#buyPlan}: the orders that fit gp and slots, and every idea ranked. */
public final class BuyPlan
{
	public final List<BuyIdea> plan;
	/** All ideas in rank order (plan entries are the same objects, trimmed to the gp left). */
	public final List<BuyIdea> ideas;
	public final double deployUsed;

	public BuyPlan(List<BuyIdea> plan, List<BuyIdea> ideas, double deployUsed)
	{
		this.plan = plan;
		this.ideas = ideas;
		this.deployUsed = deployUsed;
	}
}
