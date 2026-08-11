package com.fieldservice.domain.inventory;

/**
 * @deprecated Replaced by {@link StockLedgerScopePredicateProvider} and
 * {@link StockBalanceScopePredicateProvider} after the V1 schema migration replaced
 * {@code stock_movement} with {@code stock_ledger} + {@code stock_balance}.
 */
@Deprecated(forRemoval = true)
final class StockMovementScopePredicateProvider {
    private StockMovementScopePredicateProvider() {}
}
