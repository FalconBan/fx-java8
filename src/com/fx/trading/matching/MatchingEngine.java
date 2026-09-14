package com.fx.trading.matching;

import com.fx.trading.model.*;
import com.fx.trading.settlement.ValueDateCalculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central limit order book matching engine: price-time priority.
 *
 * A LIMIT order matches while the incoming price crosses the resting best
 * price on the opposite side; any unfilled remainder rests in the book.
 * A MARKET order sweeps the book at whatever prices are available up to
 * its quantity; any unfilled remainder is killed (market orders never rest).
 *
 * Each currency pair has its own book, and each book is only ever touched
 * from within a synchronized block on that book -- this keeps the engine
 * safe if called from multiple client-facing threads, without needing a
 * single global lock across all instruments.
 */
public final class MatchingEngine {

    private final Map<CurrencyPair, OrderBook> books = new ConcurrentHashMap<>();
    private final ValueDateCalculator valueDateCalculator = new ValueDateCalculator();

    public OrderBook bookFor(CurrencyPair pair) {
        return books.computeIfAbsent(pair, OrderBook::new);
    }

    public List<Trade> submit(Order incoming, LocalDate tradeDate) {
        OrderBook book = bookFor(incoming.getPair());
        List<Trade> trades = new ArrayList<>();

        synchronized (book) {
            TreeMap<BigDecimal, Deque<Order>> opposite = book.bookFor(incoming.getSide().opposite());

            while (incoming.getRemainingQuantity().signum() > 0 && !opposite.isEmpty()) {
                Map.Entry<BigDecimal, Deque<Order>> bestLevel = opposite.firstEntry();
                BigDecimal levelPrice = bestLevel.getKey();

                if (!crosses(incoming, levelPrice)) {
                    break; // best available price no longer acceptable to the incoming order
                }

                Deque<Order> queue = bestLevel.getValue();
                Order resting = queue.peekFirst();
                if (resting == null) {
                    opposite.remove(levelPrice);
                    continue;
                }

                BigDecimal fillQty = incoming.getRemainingQuantity().min(resting.getRemainingQuantity());
                // Execution price is the RESTING order's price -- the passive side
                // that was already in the book sets the trade price.
                trades.add(makeTrade(incoming, resting, levelPrice, fillQty, tradeDate));

                incoming.reduceRemaining(fillQty);
                resting.reduceRemaining(fillQty);
                if (resting.isFullyFilled()) {
                    queue.pollFirst();
                }
                if (queue.isEmpty()) {
                    opposite.remove(levelPrice);
                }
            }

            if (incoming.getType() == OrderType.LIMIT && incoming.getRemainingQuantity().signum() > 0) {
                book.addResting(incoming);
            }
            // MARKET orders never rest: any unfilled remainder simply reflects
            // insufficient displayed liquidity at the time of the sweep.
        }

        return trades;
    }

    /**
     * Remove a resting limit order from its book. Returns false if the order is not
     * present (already fully filled, rejected, or never rested). Must be called with
     * no other thread able to match against this book concurrently -- in this codebase
     * that means holding the TradingSystem per-instrument lock.
     */
    public boolean cancelResting(Order order) {
        OrderBook book = books.get(order.getPair());
        if (book == null) {
            return false;
        }
        synchronized (book) {
            return book.removeResting(order);
        }
    }

    private boolean crosses(Order incoming, BigDecimal restingPrice) {
        if (incoming.getType() == OrderType.MARKET) {
            return true;
        }
        return incoming.getSide() == Side.BUY
                ? incoming.getPrice().compareTo(restingPrice) >= 0
                : incoming.getPrice().compareTo(restingPrice) <= 0;
    }

    private Trade makeTrade(Order incoming, Order resting, BigDecimal price, BigDecimal qty, LocalDate tradeDate) {
        Order buyOrder = incoming.getSide() == Side.BUY ? incoming : resting;
        Order sellOrder = incoming.getSide() == Side.BUY ? resting : incoming;
        LocalDate valueDate = valueDateCalculator.spotValueDate(incoming.getPair(), tradeDate);
        return new Trade(incoming.getPair(), buyOrder.getId(), sellOrder.getId(),
                buyOrder.getClientId(), sellOrder.getClientId(), price, qty, tradeDate, valueDate);
    }
}
