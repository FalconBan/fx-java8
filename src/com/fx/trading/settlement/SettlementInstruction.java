package com.fx.trading.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One currency leg of a settlement obligation arising from a trade.
 * amount > 0 means the owner RECEIVES that amount of currency on valueDate;
 * amount < 0 means the owner must PAY (deliver) it.
 *
 * A single FX spot trade produces four of these: each counterparty has one
 * leg in the base currency and one in the quote currency.
 */
public final class SettlementInstruction {

    private final long tradeId;
    private final String owner;
    private final String counterparty;
    private final String currency;
    private final BigDecimal amount;
    private final LocalDate valueDate;
    private final boolean clsEligible;
    private boolean settled = false;

    public SettlementInstruction(long tradeId, String owner, String counterparty, String currency,
                                  BigDecimal amount, LocalDate valueDate, boolean clsEligible) {
        this.tradeId = tradeId;
        this.owner = owner;
        this.counterparty = counterparty;
        this.currency = currency;
        this.amount = amount;
        this.valueDate = valueDate;
        this.clsEligible = clsEligible;
    }

    public long getTradeId() { return tradeId; }
    public String getOwner() { return owner; }
    public String getCounterparty() { return counterparty; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getValueDate() { return valueDate; }
    public boolean isClsEligible() { return clsEligible; }
    public boolean isSettled() { return settled; }
    public void markSettled() { settled = true; }

    @Override
    public String toString() {
        return String.format("Instr[trade=%d owner=%s cpty=%s %s %s valueDt=%s settled=%s]",
                tradeId, owner, counterparty, amount.toPlainString(), currency, valueDate, settled);
    }
}
