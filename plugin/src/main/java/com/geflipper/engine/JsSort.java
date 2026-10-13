package com.geflipper.engine;

import java.util.List;

/**
 * Array.prototype.sort as V8 implements it (TimSort, third_party/v8/builtins/array-sort.tq),
 * so ties and inconsistent comparators (a NaN result counts as "equal") give the same
 * order as the browser. java.util's TimSort differs in run length and rejects
 * comparators that break transitivity.
 */
final class JsSort
{
	/** A JS comparator: negative when a sorts first; NaN counts as 0. */
	interface Cmp<T>
	{
		double compare(T a, T b);
	}

	private static final int MIN_GALLOP = 7;

	private final Cmp<Object> cmp;
	private final Object[] work;
	private Object[] temp = new Object[0];
	private int minGallop = MIN_GALLOP;
	private final int[] runBase = new int[85];
	private final int[] runLen = new int[85];
	private int runs;

	@SuppressWarnings("unchecked")
	private JsSort(Object[] work, Cmp<?> cmp)
	{
		this.work = work;
		this.cmp = (Cmp<Object>) cmp;
	}

	/** Sorts the list in place. */
	static <T> void sort(List<T> list, Cmp<? super T> cmp)
	{
		Object[] a = list.toArray();
		if (a.length < 2)
		{
			return;
		}
		new JsSort(a, cmp).run();
		for (int i = 0; i < a.length; i++)
		{
			@SuppressWarnings("unchecked")
			T t = (T) a[i];
			list.set(i, t);
		}
	}

	private boolean lt(Object x, Object y)
	{
		return cmp.compare(x, y) < 0;
	}

	private void run()
	{
		int remaining = work.length;
		int low = 0;
		int minRun = minRunLength(remaining);
		while (remaining != 0)
		{
			int len = countAndMakeRun(low, low + remaining);
			if (len < minRun)
			{
				int forced = Math.min(minRun, remaining);
				binaryInsertionSort(low, low + len, low + forced);
				len = forced;
			}
			runBase[runs] = low;
			runLen[runs] = len;
			runs++;
			mergeCollapse();
			low += len;
			remaining -= len;
		}
		while (runs > 1)
		{
			int n = runs - 2;
			if (n > 0 && runLen[n - 1] < runLen[n + 1])
			{
				n--;
			}
			mergeAt(n);
		}
	}

	private static int minRunLength(int n)
	{
		int r = 0;
		while (n >= 64)
		{
			r |= n & 1;
			n >>= 1;
		}
		return n + r;
	}

	private int countAndMakeRun(int lowArg, int high)
	{
		int low = lowArg + 1;
		if (low == high)
		{
			return 1;
		}
		int len = 2;
		boolean descending = lt(work[low], work[low - 1]);
		Object prev = work[low];
		for (int i = low + 1; i < high; i++)
		{
			Object cur = work[i];
			boolean less = lt(cur, prev);
			if (descending ? !less : less)
			{
				break;
			}
			prev = cur;
			len++;
		}
		if (descending)
		{
			for (int lo = lowArg, hi = lowArg + len - 1; lo < hi; lo++, hi--)
			{
				Object t = work[lo];
				work[lo] = work[hi];
				work[hi] = t;
			}
		}
		return len;
	}

	private void binaryInsertionSort(int low, int startArg, int high)
	{
		int start = low == startArg ? startArg + 1 : startArg;
		for (; start < high; start++)
		{
			int left = low;
			int right = start;
			Object pivot = work[start];
			while (left < right)
			{
				int mid = left + ((right - left) >> 1);
				if (lt(pivot, work[mid]))
				{
					right = mid;
				}
				else
				{
					left = mid + 1;
				}
			}
			System.arraycopy(work, left, work, left + 1, start - left);
			work[left] = pivot;
		}
	}

	private boolean invariant(int n)
	{
		if (n < 2)
		{
			return true;
		}
		return runLen[n - 2] > runLen[n - 1] + runLen[n];
	}

	private void mergeCollapse()
	{
		while (runs > 1)
		{
			int n = runs - 2;
			if (!invariant(n + 1) || !invariant(n))
			{
				if (runLen[n - 1] < runLen[n + 1])
				{
					n--;
				}
				mergeAt(n);
			}
			else if (runLen[n] <= runLen[n + 1])
			{
				mergeAt(n);
			}
			else
			{
				break;
			}
		}
	}

	private void mergeAt(int i)
	{
		int baseA = runBase[i];
		int lenA = runLen[i];
		int baseB = runBase[i + 1];
		int lenB = runLen[i + 1];
		runLen[i] = lenA + lenB;
		if (i == runs - 3)
		{
			runBase[i + 1] = runBase[i + 2];
			runLen[i + 1] = runLen[i + 2];
		}
		runs--;

		int k = gallopRight(work, work[baseB], baseA, lenA, 0);
		baseA += k;
		lenA -= k;
		if (lenA == 0)
		{
			return;
		}
		lenB = gallopLeft(work, work[baseA + lenA - 1], baseB, lenB, lenB - 1);
		if (lenB == 0)
		{
			return;
		}
		if (lenA <= lenB)
		{
			mergeLow(baseA, lenA, baseB, lenB);
		}
		else
		{
			mergeHigh(baseA, lenA, baseB, lenB);
		}
	}

	private int gallopLeft(Object[] array, Object key, int base, int length, int hint)
	{
		int lastOfs = 0;
		int offset = 1;
		if (lt(array[base + hint], key))
		{
			int maxOfs = length - hint;
			while (offset < maxOfs)
			{
				if (!lt(array[base + hint + offset], key))
				{
					break;
				}
				lastOfs = offset;
				offset = (offset << 1) + 1;
				if (offset <= 0)
				{
					offset = maxOfs;
				}
			}
			if (offset > maxOfs)
			{
				offset = maxOfs;
			}
			lastOfs += hint;
			offset += hint;
		}
		else
		{
			int maxOfs = hint + 1;
			while (offset < maxOfs)
			{
				if (lt(array[base + hint - offset], key))
				{
					break;
				}
				lastOfs = offset;
				offset = (offset << 1) + 1;
				if (offset <= 0)
				{
					offset = maxOfs;
				}
			}
			if (offset > maxOfs)
			{
				offset = maxOfs;
			}
			int tmp = lastOfs;
			lastOfs = hint - offset;
			offset = hint - tmp;
		}
		lastOfs++;
		while (lastOfs < offset)
		{
			int m = lastOfs + ((offset - lastOfs) >> 1);
			if (lt(array[base + m], key))
			{
				lastOfs = m + 1;
			}
			else
			{
				offset = m;
			}
		}
		return offset;
	}

	private int gallopRight(Object[] array, Object key, int base, int length, int hint)
	{
		int lastOfs = 0;
		int offset = 1;
		if (lt(key, array[base + hint]))
		{
			int maxOfs = hint + 1;
			while (offset < maxOfs)
			{
				if (!lt(key, array[base + hint - offset]))
				{
					break;
				}
				lastOfs = offset;
				offset = (offset << 1) + 1;
				if (offset <= 0)
				{
					offset = maxOfs;
				}
			}
			if (offset > maxOfs)
			{
				offset = maxOfs;
			}
			int tmp = lastOfs;
			lastOfs = hint - offset;
			offset = hint - tmp;
		}
		else
		{
			int maxOfs = length - hint;
			while (offset < maxOfs)
			{
				if (lt(key, array[base + hint + offset]))
				{
					break;
				}
				lastOfs = offset;
				offset = (offset << 1) + 1;
				if (offset <= 0)
				{
					offset = maxOfs;
				}
			}
			if (offset > maxOfs)
			{
				offset = maxOfs;
			}
			lastOfs += hint;
			offset += hint;
		}
		lastOfs++;
		while (lastOfs < offset)
		{
			int m = lastOfs + ((offset - lastOfs) >> 1);
			if (lt(key, array[base + m]))
			{
				offset = m;
			}
			else
			{
				lastOfs = m + 1;
			}
		}
		return offset;
	}

	private Object[] temp(int n)
	{
		if (temp.length < n)
		{
			temp = new Object[n];
		}
		return temp;
	}

	private void mergeLow(int baseA, int lengthA, int baseB, int lengthB)
	{
		Object[] tmp = temp(lengthA);
		System.arraycopy(work, baseA, tmp, 0, lengthA);
		int dest = baseA;
		int cursorTemp = 0;
		int cursorB = baseB;
		work[dest++] = work[cursorB++];

		boolean copyB;
		outer:
		{
			if (--lengthB == 0)
			{
				copyB = false;
				break outer;
			}
			if (lengthA == 1)
			{
				copyB = true;
				break outer;
			}
			int mg = minGallop;
			while (true)
			{
				int winsA = 0;
				int winsB = 0;
				while (true)
				{
					if (lt(work[cursorB], tmp[cursorTemp]))
					{
						work[dest++] = work[cursorB++];
						winsB++;
						lengthB--;
						winsA = 0;
						if (lengthB == 0)
						{
							copyB = false;
							break outer;
						}
						if (winsB >= mg)
						{
							break;
						}
					}
					else
					{
						work[dest++] = tmp[cursorTemp++];
						winsA++;
						lengthA--;
						winsB = 0;
						if (lengthA == 1)
						{
							copyB = true;
							break outer;
						}
						if (winsA >= mg)
						{
							break;
						}
					}
				}
				mg++;
				boolean first = true;
				while (winsA >= MIN_GALLOP || winsB >= MIN_GALLOP || first)
				{
					first = false;
					mg = Math.max(1, mg - 1);
					minGallop = mg;
					winsA = gallopRight(tmp, work[cursorB], cursorTemp, lengthA, 0);
					if (winsA > 0)
					{
						System.arraycopy(tmp, cursorTemp, work, dest, winsA);
						dest += winsA;
						cursorTemp += winsA;
						lengthA -= winsA;
						if (lengthA == 1)
						{
							copyB = true;
							break outer;
						}
						if (lengthA == 0)
						{
							copyB = false;
							break outer;
						}
					}
					work[dest++] = work[cursorB++];
					if (--lengthB == 0)
					{
						copyB = false;
						break outer;
					}
					winsB = gallopLeft(work, tmp[cursorTemp], cursorB, lengthB, 0);
					if (winsB > 0)
					{
						System.arraycopy(work, cursorB, work, dest, winsB);
						dest += winsB;
						cursorB += winsB;
						lengthB -= winsB;
						if (lengthB == 0)
						{
							copyB = false;
							break outer;
						}
					}
					work[dest++] = tmp[cursorTemp++];
					if (--lengthA == 1)
					{
						copyB = true;
						break outer;
					}
				}
				mg++;
				minGallop = mg;
			}
		}
		if (copyB)
		{
			System.arraycopy(work, cursorB, work, dest, lengthB);
			work[dest + lengthB] = tmp[cursorTemp];
		}
		else if (lengthA > 0)
		{
			System.arraycopy(tmp, cursorTemp, work, dest, lengthA);
		}
	}

	private void mergeHigh(int baseA, int lengthA, int baseB, int lengthB)
	{
		Object[] tmp = temp(lengthB);
		System.arraycopy(work, baseB, tmp, 0, lengthB);
		int dest = baseB + lengthB - 1;
		int cursorTemp = lengthB - 1;
		int cursorA = baseA + lengthA - 1;
		work[dest--] = work[cursorA--];

		boolean copyA;
		outer:
		{
			if (--lengthA == 0)
			{
				copyA = false;
				break outer;
			}
			if (lengthB == 1)
			{
				copyA = true;
				break outer;
			}
			int mg = minGallop;
			while (true)
			{
				int winsA = 0;
				int winsB = 0;
				while (true)
				{
					if (lt(tmp[cursorTemp], work[cursorA]))
					{
						work[dest--] = work[cursorA--];
						winsA++;
						lengthA--;
						winsB = 0;
						if (lengthA == 0)
						{
							copyA = false;
							break outer;
						}
						if (winsA >= mg)
						{
							break;
						}
					}
					else
					{
						work[dest--] = tmp[cursorTemp--];
						winsB++;
						lengthB--;
						winsA = 0;
						if (lengthB == 1)
						{
							copyA = true;
							break outer;
						}
						if (winsB >= mg)
						{
							break;
						}
					}
				}
				mg++;
				boolean first = true;
				while (winsA >= MIN_GALLOP || winsB >= MIN_GALLOP || first)
				{
					first = false;
					mg = Math.max(1, mg - 1);
					minGallop = mg;
					int k = gallopRight(work, tmp[cursorTemp], baseA, lengthA, lengthA - 1);
					winsA = lengthA - k;
					if (winsA > 0)
					{
						dest -= winsA;
						cursorA -= winsA;
						System.arraycopy(work, cursorA + 1, work, dest + 1, winsA);
						lengthA -= winsA;
						if (lengthA == 0)
						{
							copyA = false;
							break outer;
						}
					}
					work[dest--] = tmp[cursorTemp--];
					if (--lengthB == 1)
					{
						copyA = true;
						break outer;
					}
					k = gallopLeft(tmp, work[cursorA], 0, lengthB, lengthB - 1);
					winsB = lengthB - k;
					if (winsB > 0)
					{
						dest -= winsB;
						cursorTemp -= winsB;
						System.arraycopy(tmp, cursorTemp + 1, work, dest + 1, winsB);
						lengthB -= winsB;
						if (lengthB == 1)
						{
							copyA = true;
							break outer;
						}
						if (lengthB == 0)
						{
							copyA = false;
							break outer;
						}
					}
					work[dest--] = work[cursorA--];
					if (--lengthA == 0)
					{
						copyA = false;
						break outer;
					}
				}
				mg++;
				minGallop = mg;
			}
		}
		if (copyA)
		{
			dest -= lengthA;
			cursorA -= lengthA;
			System.arraycopy(work, cursorA + 1, work, dest + 1, lengthA);
			work[dest] = tmp[cursorTemp];
		}
		else if (lengthB > 0)
		{
			System.arraycopy(tmp, 0, work, dest - (lengthB - 1), lengthB);
		}
	}
}
