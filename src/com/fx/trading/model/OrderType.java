package com.fx.trading.model;

/**
 * MARKET: execute immediately at best available price (aggressive / taker).
 * LIMIT: rest in the book until a matching price arrives (passive / maker),
 *        or execute immediately if it crosses the book on arrival.
 */
public enum OrderType {
    MARKET, LIMIT
}
