package com.geflipper.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * JavaScript number semantics the ported code depends on: Math.round,
 * Number.prototype.toFixed, String(number) and toLocaleString("en-US").
 */
final class Js
{
	private static final double TWO_53 = 9007199254740992d;

	private Js()
	{
	}

	static boolean finite(double x)
	{
		return !Double.isNaN(x) && !Double.isInfinite(x);
	}

	/** Math.round: nearest integer, ties toward +Infinity, NaN/Infinity unchanged, keeps -0. */
	static double round(double x)
	{
		if (!finite(x) || Math.abs(x) >= TWO_53)
		{
			return x;
		}
		if (x < 0 && x >= -0.5)
		{
			return -0d;
		}
		if (x == 0)
		{
			return x;
		}
		return (double) Math.round(x);
	}

	/** Number.prototype.toFixed(digits): exact decimal value of x, ties away from zero. */
	static String toFixed(double x, int digits)
	{
		if (Double.isNaN(x))
		{
			return "NaN";
		}
		if (Math.abs(x) >= 1e21 || Double.isInfinite(x))
		{
			return toString(x);
		}
		if (x < 0)
		{
			return "-" + toFixed(-x, digits);
		}
		return new BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).toPlainString();
	}

	/** String(x) for integer-valued numbers (and NaN/Infinity); other values use the shortest digits. */
	static String toString(double x)
	{
		if (Double.isNaN(x))
		{
			return "NaN";
		}
		if (Double.isInfinite(x))
		{
			return x > 0 ? "Infinity" : "-Infinity";
		}
		if (x == 0)
		{
			return "0";
		}
		if (x == Math.rint(x) && Math.abs(x) < TWO_53)
		{
			return Long.toString((long) x);
		}
		BigDecimal d = shortest(x);
		String digits = d.unscaledValue().abs().toString();
		int exp = digits.length() - d.scale(); // decimal point position, as in ECMA-262 Number::toString
		String sign = x < 0 ? "-" : "";
		int k = digits.length();
		if (k <= exp && exp <= 21)
		{
			return sign + digits + "0".repeat(exp - k);
		}
		if (0 < exp && exp <= 21)
		{
			return sign + digits.substring(0, exp) + "." + digits.substring(exp);
		}
		if (-6 < exp && exp <= 0)
		{
			return sign + "0." + "0".repeat(-exp) + digits;
		}
		String e = (exp - 1 >= 0 ? "+" : "-") + Math.abs(exp - 1);
		return sign + digits.charAt(0) + (k > 1 ? "." + digits.substring(1) : "") + "e" + e;
	}

	/** Math.round(x).toLocaleString("en-US") for an already-rounded x. */
	static String localeInt(double x)
	{
		if (Double.isNaN(x))
		{
			return "NaN";
		}
		if (Double.isInfinite(x))
		{
			return x > 0 ? "∞" : "-∞";
		}
		String sign = x < 0 || (x == 0 && 1 / x < 0) ? "-" : "";
		String digits;
		if (Math.abs(x) < TWO_53)
		{
			digits = Long.toString(Math.abs((long) x));
		}
		else
		{
			// ICU prints the shortest round-trip digits, padded with zeros.
			digits = shortest(Math.abs(x)).setScale(0, RoundingMode.UNNECESSARY).toPlainString();
		}
		StringBuilder sb = new StringBuilder();
		int first = digits.length() % 3 == 0 ? 3 : digits.length() % 3;
		sb.append(digits, 0, first);
		for (int i = first; i < digits.length(); i += 3)
		{
			sb.append(',').append(digits, i, i + 3);
		}
		return sign + sb;
	}

	/** Fewest significant digits that read back as x (closest such decimal). */
	private static BigDecimal shortest(double x)
	{
		BigDecimal exact = new BigDecimal(x);
		for (int p = 1; p < 17; p++)
		{
			BigDecimal d = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
			if (d.doubleValue() == x)
			{
				return d.stripTrailingZeros();
			}
		}
		return exact.round(new MathContext(17, RoundingMode.HALF_EVEN)).stripTrailingZeros();
	}
}
