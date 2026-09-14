package com.fx.trading.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A two-way price: what the market maker will buy (bid) and sell (ask/offer)
 * the base currency for. bid <= ask always; ask - bid is the spread.
 */
public final class Quote {

    private final CurrencyPair pair;
    private final BigDecimal bid;
    private final BigDecimal ask;
    private final long timestamp;

    public Quote(CurrencyPair pair, BigDecimal bid, BigDecimal ask, long timestamp) {
        if (bid.compareTo(ask) > 0) {
            throw new IllegalArgumentException("bid " + bid + " cannot exceed ask " + ask);
        }
        this.pair = pair;
        this.bid = bid;
        this.ask = ask;
        this.timestamp = timestamp;
    }

    public CurrencyPair getPair() { return pair; }
    public BigDecimal getBid() { return bid; }
    public BigDecimal getAsk() { return ask; }
    public long getTimestamp() { return timestamp; }

    public BigDecimal mid() {
        return bid.add(ask).divide(BigDecimal.valueOf(2), pair.quotePrecision() + 2, RoundingMode.HALF_UP);
    }

    public BigDecimal spreadInPips() {
        return ask.subtract(bid).divide(pair.pipSize(), 1, RoundingMode.HALF_UP);
    }

    @Override
    public String toString() {
        return String.format("%s %s/%s (%.1f pips)", pair, bid.toPlainString(), ask.toPlainString(),
                spreadInPips());
    }
}
