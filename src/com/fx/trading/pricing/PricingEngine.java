package com.fx.trading.pricing;

import com.fx.trading.model.CurrencyPair;
import com.fx.trading.model.Quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a market-maker's streaming ECN-style price feed: maintains a mid
 * rate per pair that random-walks over time, and quotes a bid/ask around it
 * by applying a configured spread in pips.
 *
 * Interview note: this models "streaming" (ECN) pricing, where a continuous
 * two-way price is pushed to all subscribers. It's a useful contrast to
 * RFQ (request-for-quote), where a client asks for a price on a specific
 * size and a dealer responds with a one-off, often size-tiered, quote.
 */
public final class PricingEngine {

    private final Map<CurrencyPair, BigDecimal> midRates = new ConcurrentHashMap<>();
    private final Map<CurrencyPair, BigDecimal> spreadPips = new ConcurrentHashMap<>();

    public void registerPair(CurrencyPair pair, BigDecimal startingMid, BigDecimal defaultSpreadPips) {
        midRates.put(pair, startingMid);
        spreadPips.put(pair, defaultSpreadPips);
    }

    /** Advance the mid rate by a small random walk step (simulated tick). */
    public void tick(CurrencyPair pair) {
        // compute() makes read-modify-write atomic per key; ThreadLocalRandom avoids
        // contention on a shared Random instance.
        midRates.compute(pair, (p, mid) -> {
            if (mid == null) {
                throw new IllegalStateException("Pair not registered: " + p);
            }
            double pipsMove = (ThreadLocalRandom.current().nextDouble() - 0.5) * 6.0;
            BigDecimal move = p.pipSize().multiply(BigDecimal.valueOf(pipsMove));
            BigDecimal newMid = mid.add(move).setScale(p.quotePrecision() + 2, RoundingMode.HALF_UP);
            if (newMid.signum() <= 0) {
                return mid; // guard against pathological negative rates
            }
            return newMid;
        });
    }

    public Quote currentQuote(CurrencyPair pair) {
        BigDecimal mid = midRates.get(pair);
        BigDecimal spread = spreadPips.get(pair);
        if (mid == null) {
            throw new IllegalStateException("Pair not registered: " + pair);
        }
        BigDecimal halfSpread = pair.pipSize().multiply(spread).divide(BigDecimal.valueOf(2),
                pair.quotePrecision() + 2, RoundingMode.HALF_UP);
        BigDecimal bid = mid.subtract(halfSpread).setScale(pair.quotePrecision(), RoundingMode.HALF_UP);
        BigDecimal ask = mid.add(halfSpread).setScale(pair.quotePrecision(), RoundingMode.HALF_UP);
        return new Quote(pair, bid, ask, System.currentTimeMillis());
    }

    /** Widen or tighten the spread, e.g. in response to a volatility/risk event. */
    public void setSpreadPips(CurrencyPair pair, BigDecimal pips) {
        spreadPips.put(pair, pips);
    }
}
