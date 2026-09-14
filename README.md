# FX Trading System — Java 8 vs Java 21

A realistic (but dependency-free, pure-JDK) FX trading pipeline:

```
Pricing (streaming quotes) -> Risk (pre-trade checks) -> Matching (price-time
priority order book) -> Risk (position update) -> Settlement (value dates,
netting, Herstatt/CLS exposure)
```

Two implementations of the *same design*, so the diff is the point:

- `fx-java8/`  — baseline, Java 8 idioms only (no var, no records, no
  pattern matching, no virtual threads).
- `fx-java21/` — same architecture, refactored onto modern Java.

## Domain covered

- **Pricing**: a streaming ECN-style two-way quote per pair (bid/ask around
  a random-walking mid), with pip size handled correctly per pair (JPY
  crosses quote to 2dp, everything else to 4dp).
- **Matching**: a classic price-time (FIFO) priority limit order book.
  Market orders sweep the book and never rest; limit orders rest if they
  don't fully fill on arrival.
- **Risk**: pre-trade checks (max single-order size, max net open position
  per client per pair) that run *before* an order reaches the book, plus
  post-trade position/weighted-average-price/realized-P&L tracking.
- **Settlement**: value-date calculation using the real T+2 spot convention,
  including the T+1 exceptions (USD/CAD, USD/PHP, USD/RUB, USD/TRY);
  per-leg settlement instructions; bilateral netting; and a simple
  Herstatt-risk exposure metric that flags unsettled "pay" legs on
  non-CLS-eligible currencies (CLS = Continuous Linked Settlement, the
  payment-versus-payment system that eliminates this risk for major
  currencies).

The demo (`demo/Main.java` in each project) runs all of this end-to-end:
streaming ticks, liquidity providers seeding the book, a mix of market/limit
client orders (including one that gets rejected by risk, and one non-CLS
USD/TRY trade), and an end-of-day report of positions, netted settlement
obligations, and settlement-risk exposure.

## What changed between the two versions

| Concern | Java 8 | Java 21 |
|---|---|---|
| `CurrencyPair`, `Quote`, `Trade` | plain classes, manual `equals`/`hashCode`/`toString` | `record`s — immutability and boilerplate for free |
| Market vs. Limit order | `OrderType` enum + a `price` field that's `null` for MARKET | sealed interface `OrderKind` with `Market()` / `Limit(price)` records — a market order has no price field to *be* null |
| Risk check result | class with a `boolean passed` + nullable `reason` | sealed interface `RiskCheckResult` (`Pass` / `Reject(reason)`), consumed with an **exhaustive** pattern-matching `switch` — no `default`, and the compiler forces handling of any new variant |
| Matching engine "does this cross?" | `if/else` on `OrderType` | `switch` with a **record deconstruction pattern**: `case OrderKind.Limit(var price) -> ...` |
| Per-book concurrency | `synchronized (book)` | `ReentrantLock` per book — avoids pinning a virtual thread to its carrier during a held lock |
| Concurrent order submission | not demonstrated | a whole demo section fires 20 orders from different (simulated) clients using `Executors.newVirtualThreadPerTaskExecutor()` — one thread per order is cheap enough to be a reasonable default at this scale |
| `Position` | mutable class | **still a mutable class** — deliberately *not* a record, since it accumulates state; the README/code comments call out that not everything should become a record |
| Misc syntax | explicit types, `String.format` | `var`, `String.formatted(...)`, text-block-friendly style |

## Build & run

Both are plain `javac`/`java`, no build tool, no external dependencies.

```bash
# Java 8
cd fx-java8/src
javac -d ../out $(find . -name "*.java")
cd ../out && java com.fx.trading.demo.Main

# Java 21
cd fx-java21/src
javac -d ../out $(find . -name "*.java")
cd ../out && java com.fx.trading.demo.Main
```

### Tests (Java 8 project)

Dependency-free self-test runner — no JUnit, keeps the zero-dependency property:

```bash
cd fx-java8
javac -d out/test $(find src test -name "*.java")
java -cp out/test com.fx.trading.test.SelfTest
```

Covers: FIFO price-time matching, market-order book sweeps, resting/cancelled
orders, pre-trade risk rejection, position P&L (partial close + flip),
value dates (T+1/T+2, weekend skip), settlement netting symmetry,
per-currency non-CLS exposure, and concurrent same-client limit enforcement.

## Where to take this next (interview-relevant extensions)

- Swap the in-memory book for something that supports order cancel/replace
  and partial-fill acknowledgements (drop-copy style).
- Add an RFQ pricing path alongside the streaming ECN path, to contrast the
  two microstructures directly in code.
- Model holiday calendars per currency in `ValueDateCalculator` (a real spot
  trade must avoid holidays in *both* currencies, not just weekends).
- Add a simple VaR-style or scenario-shock risk check alongside the flat
  notional/position limits.
- In the Java 21 version, try `StructuredTaskScope` (currently a preview
  API) for fanning out multi-pair pricing lookups with automatic
  cancellation on first failure.
