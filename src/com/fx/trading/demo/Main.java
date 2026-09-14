package com.fx.trading.demo;

import com.fx.trading.engine.TradingSystem;
import com.fx.trading.matching.MatchingEngine;
import com.fx.trading.model.*;
import com.fx.trading.pricing.PricingEngine;
import com.fx.trading.risk.RiskEngine;
import com.fx.trading.risk.RiskLimits;
import com.fx.trading.settlement.SettlementEngine;
import com.fx.trading.settlement.SettlementInstruction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * End-to-end simulation: streaming prices -> a seeded liquidity book ->
 * client order flow through risk and matching -> settlement obligations.
 *
 * This is a Java 8 baseline implementation. See the fx-java21 sibling
 * project for the same design refactored onto Java 21 language features.
 */
public final class Main {

    public static void main(String[] args) {
        CurrencyPair eurUsd = CurrencyPair.of("EUR/USD");
        CurrencyPair usdJpy = CurrencyPair.of("USD/JPY");
        CurrencyPair usdTry = CurrencyPair.of("USD/TRY"); // T+1, non-CLS: highlights both edge cases

        PricingEngine pricingEngine = new PricingEngine();
        pricingEngine.registerPair(eurUsd, new BigDecimal("1.0850"), new BigDecimal("1.0"));
        pricingEngine.registerPair(usdJpy, new BigDecimal("149.50"), new BigDecimal("1.5"));
        pricingEngine.registerPair(usdTry, new BigDecimal("34.20"), new BigDecimal("15.0"));

        MatchingEngine matchingEngine = new MatchingEngine();
        RiskEngine riskEngine = new RiskEngine(new RiskLimits(
                new BigDecimal("5000000"),   // max single order: 5m units of base currency
                new BigDecimal("10000000")   // max net open position: 10m units of base currency
        ));
        SettlementEngine settlementEngine = new SettlementEngine();
        TradingSystem system = new TradingSystem(pricingEngine, matchingEngine, riskEngine, settlementEngine);

        System.out.println("=== 1. Streaming market data (ECN-style two-way price) ===");
        for (int i = 0; i < 3; i++) {
            pricingEngine.tick(eurUsd);
            pricingEngine.tick(usdJpy);
            pricingEngine.tick(usdTry);
        }
        System.out.println(pricingEngine.currentQuote(eurUsd));
        System.out.println(pricingEngine.currentQuote(usdJpy));
        System.out.println(pricingEngine.currentQuote(usdTry));

        System.out.println("\n=== 2. Liquidity providers seed the EUR/USD book ===");
        Quote eurUsdQuote = pricingEngine.currentQuote(eurUsd);
        LocalDate tradeDate = LocalDate.now();

        seedResting(system, "LP1", eurUsd, Side.BUY, eurUsdQuote.getBid(), new BigDecimal("2000000"), tradeDate);
        seedResting(system, "LP2", eurUsd, Side.BUY, eurUsdQuote.getBid().subtract(eurUsd.pipSize()), new BigDecimal("3000000"), tradeDate);
        seedResting(system, "LP1", eurUsd, Side.SELL, eurUsdQuote.getAsk(), new BigDecimal("2500000"), tradeDate);
        seedResting(system, "LP2", eurUsd, Side.SELL, eurUsdQuote.getAsk().add(eurUsd.pipSize()), new BigDecimal("1500000"), tradeDate);
        System.out.print(matchingEngine.bookFor(eurUsd).depthSnapshot(5));

        System.out.println("\n=== 3. Client order flow ===");

        // ClientA buys at market -- sweeps the best offer.
        submitAndReport(system, "ClientA", eurUsd, Side.BUY, OrderType.MARKET, null,
                new BigDecimal("1000000"), tradeDate);

        // ClientB posts a passive limit buy inside the spread (adds liquidity, no immediate fill).
        submitAndReport(system, "ClientB", eurUsd, Side.BUY, OrderType.LIMIT, eurUsdQuote.getBid().add(eurUsd.pipSize()),
                new BigDecimal("500000"), tradeDate);

        // HedgeFund1 sells at market, big enough to walk through multiple price levels.
        submitAndReport(system, "HedgeFund1", eurUsd, Side.SELL, OrderType.MARKET, null,
                new BigDecimal("4000000"), tradeDate);

        // An order that breaches the single-order size limit -- should be rejected by risk, not matching.
        submitAndReport(system, "ClientA", eurUsd, Side.BUY, OrderType.MARKET, null,
                new BigDecimal("9000000"), tradeDate);

        // A USD/TRY trade: T+1 settlement, non-CLS currency -> real Herstatt-style exposure.
        Quote tryQuote = pricingEngine.currentQuote(usdTry);
        seedResting(system, "LP1", usdTry, Side.SELL, tryQuote.getAsk(), new BigDecimal("1000000"), tradeDate);
        submitAndReport(system, "ClientA", usdTry, Side.BUY, OrderType.MARKET, null,
                new BigDecimal("1000000"), tradeDate);

        System.out.println("\n=== 4. End-of-day report ===");

        System.out.println("-- Positions --");
        for (String client : new String[]{"ClientA", "ClientB", "HedgeFund1", "LP1", "LP2"}) {
            for (CurrencyPair pair : new CurrencyPair[]{eurUsd, usdTry}) {
                Position pos = riskEngine.positionFor(client, pair);
                if (pos.getNetQuantity().signum() != 0) {
                    System.out.println("  " + pos);
                }
            }
        }

        System.out.println("-- Settlement instructions (raw legs) --");
        for (SettlementInstruction ins : settlementEngine.getInstructions()) {
            System.out.println("  " + ins);
        }

        System.out.println("-- Net settlement obligations (post-netting) --");
        Map<String, BigDecimal> net = settlementEngine.netByObligation();
        for (Map.Entry<String, BigDecimal> e : net.entrySet()) {
            if (e.getValue().signum() != 0) {
                System.out.println("  " + e.getKey() + " => " + e.getValue().toPlainString());
            }
        }

        System.out.println("-- Settlement (Herstatt-style) risk exposure on non-CLS trades --");
        Map<String, BigDecimal> exposure = settlementEngine.nonClsUnsettledExposureByCurrency();
        for (Map.Entry<String, BigDecimal> e : exposure.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue().toPlainString()
                    + " (unsettled 'pay' legs on non-CLS-eligible pairs, e.g. USD/TRY)");
        }
    }

    private static void seedResting(TradingSystem system, String clientId, CurrencyPair pair, Side side,
                                     BigDecimal price, BigDecimal qty, LocalDate tradeDate) {
        Order order = new Order(system.nextOrderId(), clientId, pair, side, OrderType.LIMIT, price, qty);
        system.submitOrder(order, tradeDate, trade -> System.out.println("  [seed fill] " + trade));
    }

    private static void submitAndReport(TradingSystem system, String clientId, CurrencyPair pair, Side side,
                                         OrderType type, BigDecimal price, BigDecimal qty, LocalDate tradeDate) {
        Order order = new Order(system.nextOrderId(), clientId, pair, side, type, price, qty);
        System.out.println("Submitting: " + order);
        system.submitOrder(order, tradeDate, trade -> System.out.println("  TRADE: " + trade));
        System.out.println("  Result: " + order);
    }
}
