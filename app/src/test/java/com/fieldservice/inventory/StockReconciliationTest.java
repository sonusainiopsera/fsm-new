package com.fieldservice.inventory;

import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.ledger.LedgerSumProjection;
import com.fieldservice.inventory.ledger.StockLedgerRepository;
import com.fieldservice.inventory.repository.StockBalanceRepository;
import com.fieldservice.inventory.worker.StockReconciliationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockReconciliationTest {

    @Mock StockLedgerRepository  ledgerRepository;
    @Mock StockBalanceRepository balanceRepository;

    Clock clock = Clock.fixed(Instant.parse("2025-06-01T12:00:00Z"), ZoneOffset.UTC);

    StockReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new StockReconciliationService(
                ledgerRepository, balanceRepository, clock, new SimpleMeterRegistry());
    }

    private StockBalance balance(UUID partId, UUID locationId, int qty) {
        StockBalance b = new StockBalance(partId, locationId);
        b.adjustQuantity(qty);
        return b;
    }

    @Nested
    @DisplayName("runSweep()")
    class RunSweep {

        UUID partId   = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID locId    = UUID.fromString("22222222-2222-2222-2222-222222222222");

        @Test
        @DisplayName("returns 0 discrepancies when ledger sum matches balance")
        void noDiscrepancy_whenLedgerMatchesBalance() {
            when(ledgerRepository.sumDeltasByPartAndLocation())
                    .thenReturn(List.of(projection(partId, locId, 50L)));
            when(balanceRepository.findAll())
                    .thenReturn(List.of(balance(partId, locId, 50)));

            int discrepancies = service.runSweep();

            assertThat(discrepancies).isZero();
        }

        @Test
        @DisplayName("returns 1 discrepancy when ledger sum != balance")
        void oneDiscrepancy_whenLedgerMismatch() {
            when(ledgerRepository.sumDeltasByPartAndLocation())
                    .thenReturn(List.of(projection(partId, locId, 50L)));
            when(balanceRepository.findAll())
                    .thenReturn(List.of(balance(partId, locId, 99))); // wrong!

            int discrepancies = service.runSweep();

            assertThat(discrepancies).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 0 for a part with no ledger history and zero balance")
        void noFalsePositive_zeroBalanceNoLedger() {
            when(ledgerRepository.sumDeltasByPartAndLocation()).thenReturn(List.of());
            when(balanceRepository.findAll())
                    .thenReturn(List.of(balance(partId, locId, 0)));

            int discrepancies = service.runSweep();

            assertThat(discrepancies).isZero();
        }

        @Test
        @DisplayName("counts discrepancy when ledger has entries but balance row is missing")
        void discrepancy_whenBalanceRowMissing() {
            when(ledgerRepository.sumDeltasByPartAndLocation())
                    .thenReturn(List.of(projection(partId, locId, 30L)));
            when(balanceRepository.findAll()).thenReturn(List.of()); // no balance row

            int discrepancies = service.runSweep();

            assertThat(discrepancies).isEqualTo(1);
        }

        @Test
        @DisplayName("one failing pair does not abort the sweep of other pairs")
        void oneFailingPairDoesNotAbortOthers() {
            UUID partId2 = UUID.fromString("33333333-3333-3333-3333-333333333333");
            // pair1 mismatches, pair2 is good
            when(ledgerRepository.sumDeltasByPartAndLocation())
                    .thenReturn(List.of(
                            projection(partId,  locId, 50L),
                            projection(partId2, locId, 30L)));
            when(balanceRepository.findAll())
                    .thenReturn(List.of(
                            balance(partId,  locId, 99),  // mismatch
                            balance(partId2, locId, 30))); // match

            int discrepancies = service.runSweep();

            assertThat(discrepancies).isEqualTo(1);
        }
    }

    private static LedgerSumProjection projection(UUID partId, UUID locationId, Long totalDelta) {
        return new LedgerSumProjection() {
            public UUID getPartId()      { return partId; }
            public UUID getLocationId()  { return locationId; }
            public Long getTotalDelta()  { return totalDelta; }
        };
    }
}
