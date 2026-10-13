package com.flipplanner.engine;

/** A GE offer, in the web app's ledger JSON shape. */
public final class Offer
{
	public static final String OPEN = "open";
	public static final String DONE = "done";
	public static final String CANCELLED = "cancelled";

	public String id;
	public int itemId;
	/** "buy" or "sell". */
	public String side;
	public long qty;
	public long price;
	/** Unix milliseconds. */
	public long placedAt;
	public long filled;
	/** "open", "done" or "cancelled". */
	public String status;
	public Long closedAt;
	/** GE slot (0-7) when known. */
	public Integer slot;

	public Offer()
	{
	}

	public Offer(String id, int itemId, String side, long qty, long price, long placedAt)
	{
		this.id = id;
		this.itemId = itemId;
		this.side = side;
		this.qty = qty;
		this.price = price;
		this.placedAt = placedAt;
		this.status = OPEN;
	}

	public boolean isOpen()
	{
		return OPEN.equals(status);
	}

	public boolean isBuy()
	{
		return "buy".equals(side);
	}

	@Override
	public String toString()
	{
		return "Offer{" + id + " " + side + " " + filled + "/" + qty + "x" + itemId + " @ " + price + ", " + status + "}";
	}
}
