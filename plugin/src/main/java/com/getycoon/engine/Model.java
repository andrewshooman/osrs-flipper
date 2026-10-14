package com.getycoon.engine;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gradient-boosted tree evaluator for the LightGBM models exported by research/export_js.py
 * (a port of js/gbm.js). Load the bundled model with {@link #loadDefault()}.
 */
public final class Model
{
	public static final String RESOURCE = "/com/getycoon/flip-model.json";

	private final List<String> features;
	private final Map<String, Booster> boosters;

	private Model(List<String> features, Map<String, Booster> boosters)
	{
		this.features = Collections.unmodifiableList(features);
		this.boosters = Collections.unmodifiableMap(boosters);
	}

	/** Feature names, in the order the trees index them. */
	public List<String> features()
	{
		return features;
	}

	/** Booster names in file order (fill, fwd4_q10, fwd4_q50, fwd4_q90, hours, ret). */
	public List<String> boosterNames()
	{
		return new ArrayList<>(boosters.keySet());
	}

	/** feats: {name: value}, missing or null → NaN. Returns {boosterName: prediction}. */
	public Map<String, Double> predict(Map<String, Double> feats)
	{
		double[] x = new double[features.size()];
		for (int i = 0; i < x.length; i++)
		{
			Double v = feats.get(features.get(i));
			x[i] = v == null ? Double.NaN : v;
		}
		Map<String, Double> out = new LinkedHashMap<>();
		for (Map.Entry<String, Booster> e : boosters.entrySet())
		{
			out.put(e.getKey(), e.getValue().predict(x));
		}
		return out;
	}

	/** The model bundled with the plugin. */
	public static Model loadDefault() throws IOException
	{
		try (InputStream in = Model.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IOException("Missing resource " + RESOURCE);
			}
			return load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
	}

	/** Reads the exported JSON: {features:[...], boosters:{name:{trees:[{f,t,l,r,d,m,v}], transform}}}. */
	public static Model load(Reader json) throws IOException
	{
		JsonReader r = new JsonReader(json);
		List<String> features = null;
		Map<String, Booster> boosters = new LinkedHashMap<>();
		r.beginObject();
		while (r.hasNext())
		{
			String key = r.nextName();
			if ("features".equals(key))
			{
				features = new ArrayList<>();
				r.beginArray();
				while (r.hasNext())
				{
					features.add(r.nextString());
				}
				r.endArray();
			}
			else if ("boosters".equals(key))
			{
				r.beginObject();
				while (r.hasNext())
				{
					String name = r.nextName();
					boosters.put(name, readBooster(r));
				}
				r.endObject();
			}
			else
			{
				r.skipValue();
			}
		}
		r.endObject();
		if (features == null || boosters.isEmpty())
		{
			throw new IOException("Not a model file: needs features and boosters");
		}
		for (Booster b : boosters.values())
		{
			for (Tree t : b.trees)
			{
				for (int fi : t.f)
				{
					if (fi < 0 || fi >= features.size())
					{
						throw new IOException("Tree uses feature index " + fi + " of " + features.size());
					}
				}
			}
		}
		return new Model(features, boosters);
	}

	private static Booster readBooster(JsonReader r) throws IOException
	{
		List<Tree> trees = new ArrayList<>();
		boolean sigmoid = false;
		r.beginObject();
		while (r.hasNext())
		{
			String key = r.nextName();
			if ("trees".equals(key))
			{
				r.beginArray();
				while (r.hasNext())
				{
					trees.add(readTree(r));
				}
				r.endArray();
			}
			else if ("transform".equals(key) && r.peek() == JsonToken.STRING)
			{
				sigmoid = "sigmoid".equals(r.nextString());
			}
			else
			{
				r.skipValue();
			}
		}
		r.endObject();
		return new Booster(trees.toArray(new Tree[0]), sigmoid);
	}

	private static Tree readTree(JsonReader r) throws IOException
	{
		int[] f = new int[0];
		int[] l = new int[0];
		int[] rr = new int[0];
		int[] d = new int[0];
		int[] m = new int[0];
		double[] t = new double[0];
		double[] v = new double[0];
		r.beginObject();
		while (r.hasNext())
		{
			switch (r.nextName())
			{
				case "f":
					f = ints(r);
					break;
				case "t":
					t = doubles(r);
					break;
				case "l":
					l = ints(r);
					break;
				case "r":
					rr = ints(r);
					break;
				case "d":
					d = ints(r);
					break;
				case "m":
					m = ints(r);
					break;
				case "v":
					v = doubles(r);
					break;
				default:
					r.skipValue();
			}
		}
		r.endObject();
		return new Tree(f, t, l, rr, d, m, v);
	}

	private static int[] ints(JsonReader r) throws IOException
	{
		double[] a = doubles(r);
		int[] out = new int[a.length];
		for (int i = 0; i < a.length; i++)
		{
			out[i] = (int) a[i];
		}
		return out;
	}

	private static double[] doubles(JsonReader r) throws IOException
	{
		double[] a = new double[16];
		int n = 0;
		r.beginArray();
		while (r.hasNext())
		{
			if (n == a.length)
			{
				a = java.util.Arrays.copyOf(a, n * 2);
			}
			a[n++] = r.nextDouble();
		}
		r.endArray();
		return java.util.Arrays.copyOf(a, n);
	}

	private static final class Booster
	{
		final Tree[] trees;
		final boolean sigmoid;

		Booster(Tree[] trees, boolean sigmoid)
		{
			this.trees = trees;
			this.sigmoid = sigmoid;
		}

		double predict(double[] x)
		{
			double s = 0;
			for (Tree tr : trees)
			{
				s += tr.predict(x);
			}
			return sigmoid ? 1 / (1 + StrictMath.exp(-s)) : s;
		}
	}

	private static final class Tree
	{
		final int[] f;
		final double[] t;
		final int[] l;
		final int[] r;
		final int[] d;
		final int[] m;
		final double[] v;

		Tree(int[] f, double[] t, int[] l, int[] r, int[] d, int[] m, double[] v)
		{
			this.f = f;
			this.t = t;
			this.l = l;
			this.r = r;
			this.d = d;
			this.m = m;
			this.v = v;
		}

		double predict(double[] x)
		{
			if (f.length == 0)
			{
				return v[0];
			}
			int n = 0;
			for (;;)
			{
				double raw = x[f[n]];
				int mt = m[n];
				boolean left;
				if (Double.isNaN(raw))
				{
					left = mt == 0 ? 0 <= t[n] : d[n] == 1; // "None": NaN is treated as 0
				}
				else if (mt == 1 && raw == 0)
				{
					left = d[n] == 1;
				}
				else
				{
					left = (double) (float) raw <= t[n]; // features were float32 at training time
				}
				int c = left ? l[n] : r[n];
				if (c < 0)
				{
					return v[~c];
				}
				n = c;
			}
		}
	}
}
