package com.flipplanner.engine;

/** Number formatting, identical to js/util.js (gp, full, pct, duration). */
public final class Fmt
{
	/** Shown for missing / non-finite values. */
	public static final String NONE = "—";

	private Fmt()
	{
	}

	/** Compact gp: 9,999 · 10.0K · 250K · 1.50M · 11.0M · 2.10B. */
	public static String gp(double n)
	{
		return gp(n, false);
	}

	/** As {@link #gp(double)}; {@code plus} prefixes positive values with "+". */
	public static String gp(double n, boolean plus)
	{
		if (!Js.finite(n))
		{
			return NONE;
		}
		String sign = n < 0 ? "-" : (plus && n > 0 ? "+" : "");
		double a = Math.abs(n);
		String s;
		if (a >= 1e9)
		{
			s = Js.toFixed(a / 1e9, a >= 1e10 ? 1 : 2) + "B";
		}
		else if (a >= 1e6)
		{
			s = Js.toFixed(a / 1e6, a >= 1e7 ? 1 : 2) + "M";
		}
		else if (a >= 1e4)
		{
			s = Js.toFixed(a / 1e3, a >= 1e5 ? 0 : 1) + "K";
		}
		else
		{
			s = Js.localeInt(Js.round(a));
		}
		return sign + s;
	}

	/** Whole number with thousands separators: 1,234,567. */
	public static String full(double n)
	{
		return !Js.finite(n) ? NONE : Js.localeInt(Js.round(n));
	}

	/** Fraction as a percentage with one decimal: 0.0123 → "1.2%". */
	public static String pct(double x)
	{
		return pct(x, 1);
	}

	public static String pct(double x, int digits)
	{
		return !Js.finite(x) ? NONE : Js.toFixed(x * 100, digits) + "%";
	}

	/** Hours as 25m · 3.5h · 14h · 3d. */
	public static String duration(double hours)
	{
		if (!Js.finite(hours))
		{
			return NONE;
		}
		if (hours < 1)
		{
			return Js.toString(Math.max(1, Js.round(hours * 60))) + "m";
		}
		if (hours < 48)
		{
			return (hours < 10 ? Js.toFixed(hours, 1) : Js.toString(Js.round(hours))) + "h";
		}
		return Js.toString(Js.round(hours / 24)) + "d";
	}

	/** Math.min(hi, Math.max(lo, x)): NaN stays NaN. */
	public static double clamp(double x, double lo, double hi)
	{
		return Math.min(hi, Math.max(lo, x));
	}
}
