package com.fx.trading.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Net position for one client in one currency pair.
 * netQuantity > 0  => long the base currency (bought more than sold).
 * netQuantity < 0  => short the base currency.
 *
 * Uses weighted-average cost so realized P&L on partial closes is computed
 * against the running average entry price, the same convention a spot FX
 * blotter would use.
 */
public final class Position {

    private final String clientId;
    private final CurrencyPair pair;
    private BigDecimal netQuantity = BigDecimal.ZERO;
    private BigDecimal avgPrice = BigDecimal.ZERO;
    private BigDecimal realizedPnl = BigDecimal.ZERO; // in quote currency

    public Position(String clientId, CurrencyPair pair) {
        this.clientId = clientId;
        this.pair = pair;
    }

    public String getClientId() { return clientId; }
    public CurrencyPair getPair() { return pair; }
    public BigDecimal getNetQuantity() { return netQuantity; }
    public BigDecimal getAvgPrice() { return avgPrice; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }

    /** Apply a fill: signedQty is positive for a buy, negative for a sell. */
    public void applyFill(BigDecimal signedQty, BigDecimal price) {
        boolean sameDirectionOrFlat = netQuantity.signum() == 0
                || netQuantity.signum() == signedQty.signum();

        if (sameDirectionOrFlat) {
            // Growing (or opening) the position: roll the average price forward.
            BigDecimal newQty = netQuantity.add(signedQty);
            if (newQty.signum() != 0) {
                BigDecimal oldNotional = avgPrice.multiply(netQuantity.abs());
                BigDecimal addNotional = price.multiply(signedQty.abs());
                avgPrice = oldNotional.add(addNotional)
                        .divide(newQty.abs(), pair.quotePrecision() + 4, RoundingMode.HALF_UP);
            }
            netQuantity = newQty;
        } else {
            // Reducing or flipping the position: realize P&L on the closed portion.
            BigDecimal closingQty = signedQty.abs().min(netQuantity.abs());
            BigDecimal pnlPerUnit = netQuantity.signum() > 0
                    ? price.subtract(avgPrice)      // was long, selling: profit if price rose
                    : avgPrice.subtract(price);     // was short, buying back: profit if price fell
            realizedPnl = realizedPnl.add(pnlPerUnit.multiply(closingQty));

            BigDecimal newQty = netQuantity.add(signedQty);
            netQuantity = newQty;
            if (signedQty.abs().compareTo(closingQty) > 0) {
                // Flipped through flat to the other side; new average is this fill's price.
                avgPrice = price;
            } else if (newQty.signum() == 0) {
                avgPrice = BigDecimal.ZERO;
            }
        }
    }

    @Override
    public String toString() {
        return String.format("Position[%s %s net=%s avgPx=%s realizedPnl=%s]",
                clientId, pair, netQuantity, avgPrice.toPlainString(), realizedPnl.toPlainString());
    }
}
