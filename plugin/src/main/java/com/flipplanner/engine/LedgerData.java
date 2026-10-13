package com.flipplanner.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A ledger: {trades:[...], offers:[...]}, the same JSON shape as the web app's, so Gson can
 * read and write files the app imports.
 */
public final class LedgerData
{
	public List<Trade> trades = new ArrayList<>();
	public List<Offer> offers = new ArrayList<>();

	public LedgerData()
	{
	}

	public LedgerData(List<Trade> trades, List<Offer> offers)
	{
		this.trades = trades;
		this.offers = offers;
	}
}
