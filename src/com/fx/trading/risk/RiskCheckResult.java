package com.fx.trading.risk;

public final class RiskCheckResult {

    private static final RiskCheckResult PASS = new RiskCheckResult(true, null);

    private final boolean passed;
    private final String reason;

    private RiskCheckResult(boolean passed, String reason) {
        this.passed = passed;
        this.reason = reason;
    }

    public static RiskCheckResult pass() { return PASS; }
    public static RiskCheckResult reject(String reason) { return new RiskCheckResult(false, reason); }

    public boolean isPassed() { return passed; }
    public String getReason() { return reason; }
}
