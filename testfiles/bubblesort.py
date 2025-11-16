#!/usr/bin/env python3
"""
Bubble sort a list of numbers read from a file.

Input file can contain numbers separated by spaces, commas, or newlines, e.g.:

12, 5, 9
3  1
7

Usage:
- Default: reads from "numbers.txt" in the same directory.
- Or pass a path: python bubblesort_file.py /path/to/your/file.txt
"""

import sys
import re
from pathlib import Path

def read_numbers(path: Path):
    """
    Read integers or floats from a text file. Accepts spaces, commas, or newlines.
    Returns a list[float].
    """
    text = path.read_text(encoding="utf-8", errors="ignore")
    # Match integers or floats, with optional leading sign
    tokens = re.findall(r"[+-]?\d+(?:\.\d+)?", text)
    return [float(t) if "." in t else int(t) for t in tokens]

def bubble_sort(arr):
    """
    In-place bubble sort with early-exit optimization.
    Average/Worst: O(n^2). Best (already sorted): O(n).
    Stable: yes.
    """
    n = len(arr)
    for i in range(n):
        swapped = False
        # After each pass, the largest element is at the end, so we can stop one earlier
        for j in range(0, n - i - 1):
            if arr[j] > arr[j + 1]:
                arr[j], arr[j + 1] = arr[j + 1], arr[j]
                swapped = True
        if not swapped:
            break
    return arr

def main():
    # Default file name; you can change this
    default_path = Path("numbers.txt")
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else default_path

    if not path.is_file():
        print(f"Input file not found: {path.resolve()}")
        sys.exit(1)

    nums = read_numbers(path)
    if not nums:
        print("No numbers found in the file.")
        sys.exit(0)

    bubble_sort(nums)

    # Print to stdout
    print("Sorted numbers:")
    # Preserve int display for ints, otherwise show floats
    out = [str(int(x)) if isinstance(x, int) or (isinstance(x, float) and x.is_integer()) else str(x) for x in nums]
    print(" ".join(out))

    # Optionally write to a file next to the input
    out_path = path.with_suffix(".sorted.txt")
    out_path.write_text(" ".join(out), encoding="utf-8")
    # Comment this line out if you don’t want a file:
    print(f"Written to: {out_path.resolve()}")

if __name__ == "__main__":
    main()
