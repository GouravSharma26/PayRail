package com.payrail.ledger.domain;

/**
 * Immutable financial ledger leg: DEBIT (outflow from sender) or CREDIT (inflow to recipient).
 */
public enum LedgerLeg {
    DEBIT,
    CREDIT
}
