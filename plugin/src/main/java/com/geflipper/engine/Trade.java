package com.geflipper.engine;

/** One fill, in the web app's ledger JSON shape. */
public final class Trade
{
	public String id;
	public int itemId;
	/** "buy" or "sell". */
	public String side;
	public long qty;
	public long price;
	/** Unix milliseconds. */
	public long ts;
	/** The offer this fill belongs to, if it was logged as one. */
	public String offerId;
	public String note;
	/** Alching pays no GE tax; null means false. */
	public Boolean noTax;

	public Trade()
	{
	}

	public Trade(String id, int itemId, String side, long qty, long price, long ts)
	{
		this.id = id;
		this.itemId = itemId;
		this.side = side;
		this.qty = qty;
		this.price = price;
		this.ts = ts;
	}

	public boolean isBuy()
	{
		return "buy".equals(side);
	}

	public boolean isNoTax()
	{
		return Boolean.TRUE.equals(noTax);
	}

	@Override
	public String toString()
	{
		return "Trade{" + id + " " + side + " " + qty + "x" + itemId + " @ " + price + ", ts=" + ts + "}";
	}
}
