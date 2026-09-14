package com.fx.trading.risk;

import com.fx.trading.model.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-trade risk gating (order size, resulting net position) and post-trade
 * position keeping. In a real e-FX system this would sit directly in the
 * hot order-entry path and needs to be fast and side-effect-free on reject
 * (i.e. a rejected order must not mutate any position state).
 */
public final class RiskEngine {

    private final RiskLimits limits;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    public RiskEngine(RiskLimits limits) {
        this.limits = limits;
    }

    private String key(String clientId, CurrencyPair pair) {
        return clientId + "|" + pair.symbol();
    }

    public Position positionFor(String clientId, CurrencyPair pair) {
        return positions.computeIfAbsent(key(clientId, pair), k -> new Position(clientId, pair));
    }

    /** Pre-trade check: order size limit, and projected net position limit
     *  assuming (worst case) the whole order fills. */
    public RiskCheckResult checkNewOrder(Order order) {
        if (order.getQuantity().compareTo(limits.getMaxOrderQuantity()) > 0) {
            return RiskCheckResult.reject(String.format(
                    "Order quantity %s exceeds max order size %s",
                    order.getQuantity(), limits.getMaxOrderQuantity()));
        }

        Position position = positionFor(order.getClientId(), order.getPair());
        BigDecimal signedQty = order.getSide() == Side.BUY ? order.getQuantity() : order.getQuantity().negate();
        BigDecimal projectedNet = position.getNetQuantity().add(signedQty);

        if (projectedNet.abs().compareTo(limits.getMaxNetPositionAbs()) > 0) {
            return RiskCheckResult.reject(String.format(
                    "Projected net position %s for %s would exceed limit %s",
                    projectedNet, order.getPair(), limits.getMaxNetPositionAbs()));
        }

        return RiskCheckResult.pass();
    }

    /** Post-trade: update both counterparties' positions from an executed trade. */
    public void applyTrade(Trade trade) {
        Position buyerPos = positionFor(trade.getBuyClientId(), trade.getPair());
        buyerPos.applyFill(trade.getQuantity(), trade.getPrice());

        Position sellerPos = positionFor(trade.getSellClientId(), trade.getPair());
        sellerPos.applyFill(trade.getQuantity().negate(), trade.getPrice());
    }
}
