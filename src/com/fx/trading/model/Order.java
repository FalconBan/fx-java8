package com.fx.trading.model;

import java.math.BigDecimal;

/**
 * A client order. Limit orders carry a price; market orders do not
 * (represented here as {@code null} price -- Java 8 has no sealed types,
 * so we rely on OrderType to know when price is meaningful).
 *
 * Time priority is implicit: orders rest in arrival order within each price
 * level (FIFO), guaranteed by the matching engine's per-book lock. No explicit
 * sequence number is needed because nothing ever re-orders resting orders.
 */
public final class Order {

    private final long id;
    private final String clientId;
    private final CurrencyPair pair;
    private final Side side;
    private final OrderType type;
    private final BigDecimal price;       // null for MARKET orders
    private final BigDecimal quantity;    // original quantity, in base currency units
    private BigDecimal remainingQuantity;
    private OrderStatus status;
    private final long timestamp;

    public Order(long id, String clientId, CurrencyPair pair, Side side, OrderType type,
                 BigDecimal price, BigDecimal quantity) {
        this.id = id;
        this.clientId = clientId;
        this.pair = pair;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        this.status = OrderStatus.NEW;
        this.timestamp = System.nanoTime();
    }

    public long getId() { return id; }
    public String getClientId() { return clientId; }
    public CurrencyPair getPair() { return pair; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getRemainingQuantity() { return remainingQuantity; }
    public OrderStatus getStatus() { return status; }
    public long getTimestamp() { return timestamp; }

    public boolean isFullyFilled() {
        return remainingQuantity.signum() == 0;
    }

    public void reduceRemaining(BigDecimal fillQty) {
        this.remainingQuantity = this.remainingQuantity.subtract(fillQty);
        if (this.remainingQuantity.signum() <= 0) {
            this.status = OrderStatus.FILLED;
        } else {
            this.status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    public void reject() {
        this.status = OrderStatus.REJECTED;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    @Override
    public String toString() {
        return String.format("Order#%d[%s %s %s %s qty=%s remaining=%s price=%s status=%s]",
                id, clientId, side, type, pair, quantity, remainingQuantity,
                price == null ? "MKT" : price, status);
    }
}
