# Frozen Violation Store
# ======================
# This directory contains frozen ArchUnit violation files for pre-existing rule
# violations that have been explicitly reviewed and accepted as legacy exceptions.
#
# Format (each .txt file):
#   - File name: sanitized rule description produced by TextFileBasedViolationStore
#   - Content: one violation per line (exact ArchUnit violation message)
#
# Exception process:
#   1. Run the failing test to produce the violation text
#   2. Review the violation and decide: fix or freeze
#   3. If freezing: add a dated justification comment to this README
#   4. Commit the updated violation file; CI enforces no NEW violations of the frozen rule
#
# Current exceptions (as of 2026-08-11):
#
# LAYERING — controllers that inject repositories directly (WO-200, 2026-08-11):
#   WorkOrderController: injects WorkOrderRepository and WorkOrderHoldRepository
#     Justification: legacy architecture predating the service-layer convention;
#     tracked for refactoring in backlog item BL-2026-001.
#   WorkOrderPartsController: injects StockLocationRepository
#     Justification: same legacy batch (BL-2026-001).
#   InventoryPartsController: injects PartRepository
#     Justification: same legacy batch (BL-2026-001).
#   InventoryStockController: injects StockBalanceRepository, StockLocationRepository
#     Justification: same legacy batch (BL-2026-001).
#   InventoryMovementsController: injects StockLedgerRepository, StockLocationRepository
#     Justification: same legacy batch (BL-2026-001).
