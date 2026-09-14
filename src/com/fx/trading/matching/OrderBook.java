package com.fx.trading.matching;

import com.fx.trading.model.CurrencyPair;
import com.fx.trading.model.Order;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

/**
 * A single-instrument limit order book with price-time (FIFO) priority --
 * the standard matching convention for an ECN / central limit order book.
 * Bids are kept best-first (highest price first); asks best-first
 * (lowest price first). Within a price level, orders are FIFO by arrival.
 */
public final class OrderBook {

    private final CurrencyPair pair;
    private final TreeMap<BigDecimal, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<BigDecimal, Deque<Order>> asks = new TreeMap<>(Comparator.naturalOrder());

    public OrderBook(CurrencyPair pair) {
        this.pair = pair;
    }

    public CurrencyPair getPair() { return pair; }

    TreeMap<BigDecimal, Deque<Order>> bookFor(com.fx.trading.model.Side side) {
        return side == com.fx.trading.model.Side.BUY ? bids : asks;
    }

    public Map.Entry<BigDecimal, Deque<Order>> bestBid() {
        return bids.firstEntry();
    }

    public Map.Entry<BigDecimal, Deque<Order>> bestAsk() {
        return asks.firstEntry();
    }

    /** Rest a (partially) unfilled limit order in the book. */
    void addResting(Order order) {
        TreeMap<BigDecimal, Deque<Order>> book = bookFor(order.getSide());
        book.computeIfAbsent(order.getPrice(), p -> new LinkedList<>()).addLast(order);
    }

    void removeIfEmpty(BigDecimal price, com.fx.trading.model.Side side) {
        TreeMap<BigDecimal, Deque<Order>> book = bookFor(side);
        Deque<Order> level = book.get(price);
        if (level != null && level.isEmpty()) {
            book.remove(price);
        }
    }

    /** Remove a resting order by identity. Returns false if it was not in the book. */
    boolean removeResting(Order order) {
        TreeMap<BigDecimal, Deque<Order>> book = bookFor(order.getSide());
        Deque<Order> level = book.get(order.getPrice());
        if (level == null || !level.remove(order)) {
            return false;
        }
        if (level.isEmpty()) {
            book.remove(order.getPrice());
        }
        order.cancel();
        return true;
    }

    public String depthSnapshot(int levels) {
        StringBuilder sb = new StringBuilder();
        sb.append("Book ").append(pair).append(":\n");
        sb.append("  ASKS (best first):\n");
        asks.entrySet().stream().limit(levels).forEach(e ->
                sb.append("    ").append(e.getKey()).append(" x ").append(totalQty(e.getValue())).append('\n'));
        sb.append("  BIDS (best first):\n");
        bids.entrySet().stream().limit(levels).forEach(e ->
                sb.append("    ").append(e.getKey()).append(" x ").append(totalQty(e.getValue())).append('\n'));
        return sb.toString();
    }

    private BigDecimal totalQty(Deque<Order> level) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Order o : level) sum = sum.add(o.getRemainingQuantity());
        return sum;
    }
}
