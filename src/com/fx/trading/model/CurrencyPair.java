package com.fx.trading.model;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents an FX currency pair, e.g. EUR/USD.
 *
 * Interview note: "pip" location depends on the pair. Most pairs quote to
 * 4 decimal places (pip = 0.0001), but JPY crosses quote to 2 decimal places
 * (pip = 0.01).
 *
 * Interview trap: spot FX settles T+2 by convention (trade date + 2 business
 * days) -- EXCEPT a handful of pairs that settle T+1 for historical /
 * regulatory reasons: USD/CAD, USD/PHP, USD/RUB, USD/TRY. This class encodes
 * that exception list so the settlement engine can compute value dates
 * correctly instead of assuming a blanket T+2.
 */
public final class CurrencyPair {

    private static final Set<String> T_PLUS_1_PAIRS = new HashSet<>(Arrays.asList(
            "USD/CAD", "USD/PHP", "USD/RUB", "USD/TRY"
    ));

    private final String base;
    private final String quote;

    public CurrencyPair(String base, String quote) {
        this.base = base.toUpperCase();
        this.quote = quote.toUpperCase();
    }

    public static CurrencyPair of(String symbol) {
        String[] parts = symbol.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected BASE/QUOTE, got: " + symbol);
        }
        return new CurrencyPair(parts[0], parts[1]);
    }

    public String getBase() {
        return base;
    }

    public String getQuote() {
        return quote;
    }

    public String symbol() {
        return base + "/" + quote;
    }

    /** Decimal places at which this pair is quoted. */
    public int quotePrecision() {
        return quote.equals("JPY") ? 2 : 4;
    }

    /** Size of one pip for this pair, expressed as a BigDecimal. */
    public BigDecimal pipSize() {
        return BigDecimal.ONE.scaleByPowerOfTen(-quotePrecision());
    }

    /** Standard spot settlement lag in business days (T+1 or T+2). */
    public int spotSettlementDays() {
        return T_PLUS_1_PAIRS.contains(symbol()) ? 1 : 2;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CurrencyPair)) return false;
        CurrencyPair that = (CurrencyPair) o;
        return base.equals(that.base) && quote.equals(that.quote);
    }

    @Override
    public int hashCode() {
        return base.hashCode() * 31 + quote.hashCode();
    }

    @Override
    public String toString() {
        return symbol();
    }
}
