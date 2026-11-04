package com.truesim.ui.charts;

/** How an axis maps data to pixels and where it puts its ticks. */
public enum Scale {
    /** Evenly: ticks at nice numbers (1, 2, 5 times a power of ten). */
    LINEAR,
    /** By the logarithm (positive data only): ticks at powers of ten, minor ticks at 2 to 9 times them. */
    LOG,
    /**
     * Symmetric logarithm: linear within {@code ±constant} of zero, logarithmic beyond, both signs (ticks at zero and
     * signed powers of ten times the constant).
     */
    SYMLOG,
    /** Time: data in seconds since 1970-01-01 UTC (fractions down to microseconds); calendar ticks and labels. */
    TIME,
    /** Categories: whole numbers 0, 1, 2... labelled by name. */
    CATEGORY
}
