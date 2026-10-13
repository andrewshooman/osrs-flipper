package com.flipplanner.engine;

/** What to do with a holding (see {@link Decide#holdingAction}). */
public final class HoldingAction
{
	/** "wait", "cut", "hold", "sell" or "list". */
	public final String action;
	/** Price to list at; null for "wait", NaN if the bar has no low price. */
	public final Double price;
	public final String label;
	/** "muted", "warn", "bad" or "good". */
	public final String tone;

	public HoldingAction(String action, Double price, String label, String tone)
	{
		this.action = action;
		this.price = price;
		this.label = label;
		this.tone = tone;
	}

	@Override
	public String toString()
	{
		return action + ": " + label;
	}
}
