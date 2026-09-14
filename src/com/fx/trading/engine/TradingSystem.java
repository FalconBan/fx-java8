package com.fx.trading.engine;

import com.fx.trading.matching.MatchingEngine;
import com.fx.trading.model.CurrencyPair;
import com.fx.trading.model.Order;
import com.fx.trading.model.Trade;
import com.fx.trading.pricing.PricingEngine;
import com.fx.trading.risk.RiskCheckResult;
import com.fx.trading.risk.RiskEngine;
import com.fx.trading.settlement.SettlementEngine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Wires the pipeline together: order -> pre-trade risk check -> matching ->
 * post-trade risk (position) update -> settlement instruction generation.
 *
 * This is the "full pipeline" entry point a client-facing order gateway
 * would call.
 */
public final class TradingSystem {

    private static final AtomicLong ORDER_IDS = new AtomicLong(0);

    /** One lock per instrument, so the check -> match -> apply sequence is atomic. */
    private final Map<CurrencyPair, Object> pairLocks = new ConcurrentHashMap<>();

    private final PricingEngine pricingEngine;
    private final MatchingEngine matchingEngine;
    private final RiskEngine riskEngine;
    private final SettlementEngine settlementEngine;

    public TradingSystem(PricingEngine pricingEngine, MatchingEngine matchingEngine,
                          RiskEngine riskEngine, SettlementEngine settlementEngine) {
        this.pricingEngine = pricingEngine;
        this.matchingEngine = matchingEngine;
        this.riskEngine = riskEngine;
        this.settlementEngine = settlementEngine;
    }

    public long nextOrderId() {
        return ORDER_IDS.incrementAndGet();
    }

    /**
     * Submit an order through the full pipeline.
     *
     * The pre-trade check, matching and post-trade position update run under
     * one per-instrument lock so that concurrent orders from the same client
     * cannot both pass the projected-position limit on a stale read (TOCTOU).
     * Different instruments proceed in parallel.
     *
     * @param onTrade callback invoked for each resulting trade (e.g. for logging/reporting)
     */
    public Order submitOrder(Order order, LocalDate tradeDate, Consumer<Trade> onTrade) {
        synchronized (pairLock(order.getPair())) {
            RiskCheckResult check = riskEngine.checkNewOrder(order);
            if (!check.isPassed()) {
                order.reject();
                return order;
            }

            List<Trade> trades = matchingEngine.submit(order, tradeDate);
            for (Trade trade : trades) {
                riskEngine.applyTrade(trade);
                settlementEngine.recordTrade(trade);
                if (onTrade != null) {
                    onTrade.accept(trade);
                }
            }
        }
        return order;
    }

    /**
     * Cancel a resting limit order. Returns true if the order was found and cancelled,
     * false if it is not in the book (already filled/rejected). The per-instrument lock
     * guarantees no fill can interleave between removal and status update.
     */
    public boolean cancelOrder(Order order) {
        synchronized (pairLock(order.getPair())) {
            return matchingEngine.cancelResting(order);
        }
    }

    private Object pairLock(CurrencyPair pair) {
        return pairLocks.computeIfAbsent(pair, p -> new Object());
    }

    public PricingEngine getPricingEngine() { return pricingEngine; }
    public MatchingEngine getMatchingEngine() { return matchingEngine; }
    public RiskEngine getRiskEngine() { return riskEngine; }
    public SettlementEngine getSettlementEngine() { return settlementEngine; }
}
