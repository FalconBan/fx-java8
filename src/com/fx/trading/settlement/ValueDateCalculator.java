package com.fx.trading.settlement;

import com.fx.trading.model.CurrencyPair;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Computes the spot value date for a trade: trade date + N business days,
 * where N is normally 2 (T+2) but 1 for a documented list of pairs
 * (see {@link CurrencyPair#spotSettlementDays()}).
 *
 * This simplified version only skips weekends. A production system would
 * also skip the public holidays of BOTH currencies in the pair (e.g. a
 * EUR/USD spot trade must avoid US and eurozone holidays) -- worth
 * mentioning in an interview even though it's not modelled here.
 */
public final class ValueDateCalculator {

    public LocalDate spotValueDate(CurrencyPair pair, LocalDate tradeDate) {
        return addBusinessDays(tradeDate, pair.spotSettlementDays());
    }

    public LocalDate addBusinessDays(LocalDate from, int businessDays) {
        LocalDate date = from;
        int added = 0;
        while (added < businessDays) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date;
    }
}
