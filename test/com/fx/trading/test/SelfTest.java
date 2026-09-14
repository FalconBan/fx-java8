package com.fx.trading.test;

import com.fx.trading.engine.TradingSystem;
import com.fx.trading.matching.MatchingEngine;
import com.fx.trading.model.*;
import com.fx.trading.pricing.PricingEngine;
import com.fx.trading.risk.RiskCheckResult;
import com.fx.trading.risk.RiskEngine;
import com.fx.trading.risk.RiskLimits;
import com.fx.trading.settlement.SettlementEngine;
import com.fx.trading.settlement.ValueDateCalculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free self-test runner (plain javac/java, no JUnit) so the project
 * keeps its zero-external-dependency property. Run with:
 *
 *   javac -d out/test $(find src test -name "*.java")
 *   java -cp out/test com.fx.trading.test.SelfTest
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final AtomicLong IDS = new AtomicLong(1);

    public static void main(String[] args) throws Exception {
        testOrderBookFifo();
        testMarketSweepsMultipleLevels();
        testLimitRestsWhenNotCrossing();
        testCancelRemovesRestingOrder();
        testRiskRejectsOversizeOrder();
        testPositionPartialCloseRealizesPnl();
        testPositionFlipThroughFlat();
        testValueDateT2AndT1();
        testSettlementNettingRoundTrip();
        testNonClsExposureIsPerCurrency();
        testConcurrentSameClientRespectsLimit();

        System.out.println("\n=== SelfTest: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static CurrencyPair pair(String sym) { return CurrencyPair.of(sym); }

    /** Build a fully wired system with given max order size / max net position. */
    private static TradingSystem system(long maxOrder, long maxNet) {
        PricingEngine pricing = new PricingEngine();
        MatchingEngine matching = new MatchingEngine();
        RiskEngine risk = new RiskEngine(new RiskLimits(bd(String.valueOf(maxOrder)), bd(String.valueOf(maxNet))));
        SettlementEngine settlement = new SettlementEngine();
        return new TradingSystem(pricing, matching, risk, settlement);
    }

    private static Order limit(String client, CurrencyPair p, Side side, String price, long qty) {
        return new Order(IDS.incrementAndGet(), client, p, side, OrderType.LIMIT, bd(price), bd(String.valueOf(qty)));
    }

    private static Order market(String client, CurrencyPair p, Side side, long qty) {
        return new Order(IDS.incrementAndGet(), client, p, side, OrderType.MARKET, null, bd(String.valueOf(qty)));
    }

    // ---- tests -------------------------------------------------------------

    /** FIFO within a price level: first resting order fills first. */
    private static void testOrderBookFifo() {
        MatchingEngine me = new MatchingEngine();
        CurrencyPair p = pair("EUR/USD");
        Order o1 = limit("A", p, Side.BUY, "1.0850", 100);
        Order o2 = limit("B", p, Side.BUY, "1.0850", 100);
        me.submit(o1, LocalDate.now());
        me.submit(o2, LocalDate.now());

        // A sell that only partially fills the level should hit A first (FIFO).
        Order taker = limit("C", p, Side.SELL, "1.0850", 50);
        List<Trade> trades = me.submit(taker, LocalDate.now());

        check("fifo: one trade produced", trades.size() == 1);
        check("fifo: first resting order (A) fills first",
                trades.get(0).getBuyOrderId() == o1.getId());
    }

    /** A market sell walks through multiple price levels. */
    private static void testMarketSweepsMultipleLevels() {
        MatchingEngine me = new MatchingEngine();
        CurrencyPair p = pair("EUR/USD");
        me.submit(limit("A", p, Side.BUY, "1.0850", 100), LocalDate.now());
        me.submit(limit("B", p, Side.BUY, "1.0849", 100), LocalDate.now());

        Order taker = market("C", p, Side.SELL, 150);
        List<Trade> trades = me.submit(taker, LocalDate.now());

        check("sweep: fills across two levels", trades.size() == 2);
        check("sweep: first fill at best bid 1.0850",
                trades.get(0).getPrice().compareTo(bd("1.0850")) == 0);
    }

    /** A limit buy below the best ask rests (does not fill). */
    private static void testLimitRestsWhenNotCrossing() {
        MatchingEngine me = new MatchingEngine();
        CurrencyPair p = pair("EUR/USD");
        me.submit(limit("A", p, Side.SELL, "1.0850", 100), LocalDate.now());

        Order resting = limit("B", p, Side.BUY, "1.0849", 100); // below best ask
        List<Trade> trades = me.submit(resting, LocalDate.now());

        check("rest: no immediate fill", trades.isEmpty());
        check("rest: order still resting (not filled)", !resting.isFullyFilled());
    }

    /** Cancel removes a resting order from the book. */
    private static void testCancelRemovesRestingOrder() {
        MatchingEngine me = new MatchingEngine();
        CurrencyPair p = pair("EUR/USD");
        Order resting = limit("A", p, Side.BUY, "1.0850", 100);
        me.submit(resting, LocalDate.now());

        check("cancel: returns true for a present order", me.cancelResting(resting));
        check("cancel: order marked cancelled", resting.getStatus() == OrderStatus.CANCELLED);
        check("cancel: second cancel returns false (no longer in book)", !me.cancelResting(resting));
    }

    /** Pre-trade risk rejects an order above the max size. */
    private static void testRiskRejectsOversizeOrder() {
        TradingSystem s = system(100, 1000);
        CurrencyPair p = pair("EUR/USD");
        Order tooBig = market("A", p, Side.BUY, 500);
        s.submitOrder(tooBig, LocalDate.now(), t -> { });
        check("risk: oversize order rejected", tooBig.getStatus() == OrderStatus.REJECTED);
    }

    /** Partial close realizes P&L against the running average. */
    private static void testPositionPartialCloseRealizesPnl() {
        Position pos = new Position("A", pair("EUR/USD"));
        pos.applyFill(bd("100"), bd("1.10"));   // long 100 @ avg 1.10
        pos.applyFill(bd("50"), bd("1.20"));    // long 150, avg rolls up

        check("pnl: net quantity is 150", pos.getNetQuantity().compareTo(bd("150")) == 0);

        pos.applyFill(bd("-50"), bd("1.20"));   // close 50 of the long at 1.20
        check("pnl: net quantity back to 100", pos.getNetQuantity().compareTo(bd("100")) == 0);
        check("pnl: realized P&L positive (sold above avg)", pos.getRealizedPnl().signum() > 0);
    }

    /** Flipping through flat sets the new side's average to the fill price. */
    private static void testPositionFlipThroughFlat() {
        Position pos = new Position("A", pair("EUR/USD"));
        pos.applyFill(bd("100"), bd("1.10"));  // long 100 @ 1.10
        pos.applyFill(bd("-150"), bd("1.20")); // close 100, flip to short 50

        check("flip: net quantity is -50 (short)", pos.getNetQuantity().compareTo(bd("-50")) == 0);
        check("flip: new average price is the fill price 1.20", pos.getAvgPrice().compareTo(bd("1.20")) == 0);
    }

    /** T+2 for normal pairs, T+1 for the documented exceptions, weekends skipped. */
    private static void testValueDateT2AndT1() {
        ValueDateCalculator vdc = new ValueDateCalculator();
        // 2026-09-08 is a Tuesday.
        LocalDate tue = LocalDate.of(2026, 9, 8);

        check("valuedate: EUR/USD T+2 -> Thursday 2026-09-10",
                vdc.spotValueDate(pair("EUR/USD"), tue).equals(LocalDate.of(2026, 9, 10)));
        check("valuedate: USD/TRY T+1 -> Wednesday 2026-09-09",
                vdc.spotValueDate(pair("USD/TRY"), tue).equals(LocalDate.of(2026, 9, 9)));

        // Friday trade: T+2 must skip the weekend -> Tuesday.
        LocalDate fri = LocalDate.of(2026, 9, 4);
        check("valuedate: Friday T+2 skips weekend -> Tuesday 2026-09-08",
                vdc.spotValueDate(pair("EUR/USD"), fri).equals(LocalDate.of(2026, 9, 8)));
    }

    /** Buy then sell back at the same price: every bilateral obligation nets to zero. */
    private static void testSettlementNettingRoundTrip() {
        TradingSystem s = system(1000, 10000);
        CurrencyPair p = pair("EUR/USD");

        // C buys 100 from LP @ 1.0850, then sells the same 100 back to LP @ 1.0850.
        s.submitOrder(limit("LP", p, Side.SELL, "1.0850", 100), LocalDate.now(), t -> { });
        s.submitOrder(market("C", p, Side.BUY, 100), LocalDate.now(), t -> { });
        s.submitOrder(limit("LP", p, Side.BUY, "1.0850", 100), LocalDate.now(), t -> { });
        s.submitOrder(market("C", p, Side.SELL, 100), LocalDate.now(), t -> { });

        Map<String, BigDecimal> net = s.getSettlementEngine().netByObligation();
        boolean allZero = true;
        for (Map.Entry<String, BigDecimal> e : net.entrySet()) {
            if (e.getValue().signum() != 0) { allZero = false; break; }
        }
        check("netting: round-trip at same price nets every obligation to zero", allZero);
    }

    /** Non-CLS exposure is reported per currency, not summed across currencies. */
    private static void testNonClsExposureIsPerCurrency() {
        TradingSystem s = system(1000, 10000);
        CurrencyPair usdTry = pair("USD/TRY"); // TRY is non-CLS

        // C buys 100 USD: receives 100 USD, pays 3420 TRY. LP pays 100 USD, receives 3420 TRY.
        s.submitOrder(limit("LP", usdTry, Side.SELL, "34.20", 100), LocalDate.now(), t -> { });
        s.submitOrder(market("C", usdTry, Side.BUY, 100), LocalDate.now(), t -> { });

        Map<String, BigDecimal> exposure = s.getSettlementEngine().nonClsUnsettledExposureByCurrency();
        check("herstatt: USD pay leg present", exposure.containsKey("USD"));
        check("herstatt: TRY pay leg present", exposure.containsKey("TRY"));
        check("herstatt: TRY exposure equals quote notional 3420",
                exposure.getOrDefault("TRY", BigDecimal.ZERO).compareTo(bd("3420")) == 0);
    }

    /** Concurrent orders from the same client must not both pass a position limit. */
    private static void testConcurrentSameClientRespectsLimit() throws Exception {
        TradingSystem s = system(1000, 500); // max order 1000 (seed passes), max net 500 per client
        CurrencyPair p = pair("EUR/USD");

        // Seed exactly 500 units of liquidity: at most 5 market buys of 100 can fill.
        s.submitOrder(limit("LP", p, Side.SELL, "1.0850", 500), LocalDate.now(), t -> { });

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        Thread[] workers = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            final long id = IDS.incrementAndGet();
            workers[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                Order o = new Order(id, "SAMECLIENT", p, Side.BUY, OrderType.MARKET, null, bd("100"));
                s.submitOrder(o, LocalDate.now(), t -> { });
                if (o.getStatus() == OrderStatus.REJECTED) rejected.incrementAndGet();
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread w : workers) w.join();

        // Without the per-instrument lock, all 8 threads read net=0, all pass the check,
        // and all fill -> net position 800 > limit 500. With the lock: exactly 3 rejected.
        check("concurrency: exactly 3 of 8 concurrent orders rejected by position limit",
                rejected.get() == 3);
        check("concurrency: final net position equals the limit (500)",
                s.getRiskEngine().positionFor("SAMECLIENT", p).getNetQuantity().compareTo(bd("500")) == 0);
    }
}
