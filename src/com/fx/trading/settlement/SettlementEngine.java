package com.fx.trading.settlement;

import com.fx.trading.model.Trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns executed trades into settlement obligations, nets them where
 * possible, and tracks exposure to settlement ("Herstatt") risk.
 *
 * Interview background:
 * - Herstatt risk is the risk that one party pays away the currency it owes
 *   before receiving the currency it is due, because the two legs settle in
 *   different time zones/systems and are not truly simultaneous. Named after
 *   Bankhaus Herstatt's 1974 failure, which happened between the close of
 *   the German payment system and the opening of the US one.
 * - CLS (Continuous Linked Settlement) largely eliminates this for CLS
 *   currencies by settling both legs payment-versus-payment (PvP): each
 *   leg only settles if the other does, atomically, in central-bank money.
 * - CLS covers a fixed list of major currencies. Trades entirely in CLS
 *   currencies can settle PvP; anything else still settles bilaterally and
 *   carries genuine Herstatt-style exposure. This engine flags that.
 */
public final class SettlementEngine {

    private static final Set<String> CLS_ELIGIBLE = new HashSet<>(Arrays.asList(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD",
            "SEK", "NOK", "DKK", "HKD", "KRW", "SGD", "ZAR", "ILS", "MXN", "HUF"
    ));

    private final List<SettlementInstruction> instructions =
            Collections.synchronizedList(new ArrayList<SettlementInstruction>());

    /** Generate the four settlement legs (two per counterparty) for a trade. */
    public List<SettlementInstruction> recordTrade(Trade trade) {
        BigDecimal baseAmount = trade.getQuantity();
        BigDecimal quoteAmount = trade.quoteNotional();
        String base = trade.getPair().getBase();
        String quote = trade.getPair().getQuote();
        boolean cls = isClsEligible(trade);

        List<SettlementInstruction> legs = Arrays.asList(
                new SettlementInstruction(trade.getId(), trade.getBuyClientId(), trade.getSellClientId(),
                        base, baseAmount, trade.getValueDate(), cls),
                new SettlementInstruction(trade.getId(), trade.getBuyClientId(), trade.getSellClientId(),
                        quote, quoteAmount.negate(), trade.getValueDate(), cls),
                new SettlementInstruction(trade.getId(), trade.getSellClientId(), trade.getBuyClientId(),
                        base, baseAmount.negate(), trade.getValueDate(), cls),
                new SettlementInstruction(trade.getId(), trade.getSellClientId(), trade.getBuyClientId(),
                        quote, quoteAmount, trade.getValueDate(), cls)
        );
        instructions.addAll(legs);
        return legs;
    }

    public boolean isClsEligible(Trade trade) {
        return CLS_ELIGIBLE.contains(trade.getPair().getBase()) && CLS_ELIGIBLE.contains(trade.getPair().getQuote());
    }

    /**
     * Bilateral net settlement amount per (owner, counterparty, currency, valueDate).
     * This is what CLS-style multilateral netting (or simple bilateral netting)
     * would actually move, instead of settling every trade gross.
     */
    public Map<String, BigDecimal> netByObligation() {
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        synchronized (instructions) {
            for (SettlementInstruction ins : instructions) {
                String k = ins.getOwner() + "|" + ins.getCounterparty() + "|" + ins.getCurrency() + "|" + ins.getValueDate();
                net.merge(k, ins.getAmount(), BigDecimal::add);
            }
        }
        return net;
    }

    /**
     * Unsettled "pay" legs on non-CLS-eligible trades, grouped by currency.
     *
     * Returns a map of currency -> total unsettled pay amount in that currency.
     * (The previous version summed USD and TRY legs into one number -- adding
     * different currencies together is meaningless; exposure must stay per-currency.)
     */
    public Map<String, BigDecimal> nonClsUnsettledExposureByCurrency() {
        Map<String, BigDecimal> exposure = new LinkedHashMap<>();
        synchronized (instructions) {
            for (SettlementInstruction ins : instructions) {
                if (!ins.isSettled() && !ins.isClsEligible() && ins.getAmount().signum() < 0) {
                    // Only the "pay" legs represent principal actually at risk of
                    // being sent before the offsetting receipt is confirmed.
                    exposure.merge(ins.getCurrency(), ins.getAmount().abs(), BigDecimal::add);
                }
            }
        }
        return exposure;
    }

    public List<SettlementInstruction> getInstructions() {
        synchronized (instructions) {
            return new ArrayList<>(instructions);
        }
    }
}
