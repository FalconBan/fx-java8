package com.fx.trading.risk;

import java.math.BigDecimal;

/** Simple pre-trade risk configuration. A real desk would tier this by
 *  client, pair, and time of day, and would likely also include VaR-style
 *  or stress-scenario limits -- kept simple here to focus on the mechanics. */
public final class RiskLimits {

    private final BigDecimal maxOrderQuantity;      // per single order, in base currency units
    private final BigDecimal maxNetPositionAbs;      // per client per pair, in base currency units

    public RiskLimits(BigDecimal maxOrderQuantity, BigDecimal maxNetPositionAbs) {
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxNetPositionAbs = maxNetPositionAbs;
    }

    public BigDecimal getMaxOrderQuantity() { return maxOrderQuantity; }
    public BigDecimal getMaxNetPositionAbs() { return maxNetPositionAbs; }
}
