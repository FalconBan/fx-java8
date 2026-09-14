package com.fx.trading.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/** An executed trade produced by the matching engine. */
public final class Trade {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private final long id;
    private final CurrencyPair pair;
    private final long buyOrderId;
    private final long sellOrderId;
    private final String buyClientId;
    private final String sellClientId;
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final LocalDate tradeDate;
    private final LocalDate valueDate;

    public Trade(CurrencyPair pair, long buyOrderId, long sellOrderId, String buyClientId,
                 String sellClientId, BigDecimal price, BigDecimal quantity,
                 LocalDate tradeDate, LocalDate valueDate) {
        this.id = SEQUENCE.incrementAndGet();
        this.pair = pair;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.buyClientId = buyClientId;
        this.sellClientId = sellClientId;
        this.price = price;
        this.quantity = quantity;
        this.tradeDate = tradeDate;
        this.valueDate = valueDate;
    }

    public long getId() { return id; }
    public CurrencyPair getPair() { return pair; }
    public long getBuyOrderId() { return buyOrderId; }
    public long getSellOrderId() { return sellOrderId; }
    public String getBuyClientId() { return buyClientId; }
    public String getSellClientId() { return sellClientId; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getQuantity() { return quantity; }
    public LocalDate getTradeDate() { return tradeDate; }
    public LocalDate getValueDate() { return valueDate; }

    /** Notional in quote currency (e.g. USD amount for a EUR/USD trade). */
    public BigDecimal quoteNotional() {
        return price.multiply(quantity);
    }

    @Override
    public String toString() {
        return String.format("Trade#%d[%s qty=%s @ %s buyer=%s seller=%s tradeDt=%s valueDt=%s]",
                id, pair, quantity, price.toPlainString(), buyClientId, sellClientId, tradeDate, valueDate);
    }
}
