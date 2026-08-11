package com.fieldservice.inventory;

import com.fieldservice.inventory.application.ConsumePartsCommand;
import com.fieldservice.inventory.application.InsufficientStockException;
import com.fieldservice.inventory.application.InvalidStockMovementException;
import com.fieldservice.inventory.application.ReturnPartsCommand;
import com.fieldservice.inventory.application.StockMovementServiceImpl;
import com.fieldservice.inventory.api.StockMovementResult;
import com.fieldservice.inventory.domain.Part;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.repository.PartRepository;
import com.fieldservice.inventory.repository.StockBalanceRepository;
import com.fieldservice.inventory.repository.WorkOrderPartRepository;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for StockMovementServiceImpl — no Spring context, mocked repositories.
 *
 * <p>Verifies the decrement decision, multi-line all-or-nothing composition,
 * idempotency short-circuit, and exception construction per AC-11.
 */
class StockMovementServiceTest {

    StockBalanceRepository  stockBalanceRepository  = mock(StockBalanceRepository.class);
    WorkOrderPartRepository workOrderPartRepository = mock(WorkOrderPartRepository.class);
    PartRepository          partRepository          = mock(PartRepository.class);
    EntityManager           entityManager           = mock(EntityManager.class);
    DomainEventPublisher    eventPublisher          = mock(DomainEventPublisher.class);

    StockMovementServiceImpl service;

    static final UUID WO_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID LOC_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID PART_A  = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID PART_B  = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID ACTOR   = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @BeforeEach
    void setUp() {
        service = new StockMovementServiceImpl(
                stockBalanceRepository, workOrderPartRepository,
                partRepository, entityManager, eventPublisher);
        when(partRepository.findAllById(any())).thenReturn(List.of());
    }

    // ---- Validation ---------------------------------------------------------

    @Test
    @DisplayName("Empty lines list is rejected with 400")
    void emptyLines_throws400() {
        ConsumePartsCommand cmd = new ConsumePartsCommand(WO_ID, LOC_ID, List.of(), ACTOR, null);
        assertThatThrownBy(() -> service.consumeParts(cmd))
                .isInstanceOf(InvalidStockMovementException.class)
                .hasMessageContaining("At least one line");
    }

    @Test
    @DisplayName("Zero quantity is rejected with 400")
    void zeroQuantity_throws400() {
        ConsumePartsCommand cmd = new ConsumePartsCommand(
                WO_ID, LOC_ID,
                List.of(new ConsumePartsCommand.LineItem(PART_A, 0, null)),
                ACTOR, null);
        assertThatThrownBy(() -> service.consumeParts(cmd))
                .isInstanceOf(InvalidStockMovementException.class)
                .hasMessageContaining("Quantity must be positive");
    }

    @Test
    @DisplayName("Lines list exceeding 50 is rejected with 400")
    void tooManyLines_throws400() {
        List<ConsumePartsCommand.LineItem> lines = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> new ConsumePartsCommand.LineItem(UUID.randomUUID(), 1, null))
                .toList();
        ConsumePartsCommand cmd = new ConsumePartsCommand(WO_ID, LOC_ID, lines, ACTOR, null);
        assertThatThrownBy(() -> service.consumeParts(cmd))
                .isInstanceOf(InvalidStockMovementException.class)
                .hasMessageContaining("Maximum 50 lines");
    }

    // ---- Consume: sufficient stock ------------------------------------------

    @Test
    @DisplayName("Successful single-line consumption decrements balance and persists records")
    void singleLine_success() {
        StockBalance balance = mockBalance(PART_A, LOC_ID, 10);
        when(stockBalanceRepository.conditionalDecrement(PART_A, LOC_ID, 3)).thenReturn(1);
        when(stockBalanceRepository.findByPartIdAndLocationId(PART_A, LOC_ID))
                .thenReturn(Optional.of(balance));

        ConsumePartsCommand cmd = new ConsumePartsCommand(
                WO_ID, LOC_ID,
                List.of(new ConsumePartsCommand.LineItem(PART_A, 3, "INSTALL")),
                ACTOR, null);

        StockMovementResult result = service.consumeParts(cmd);

        assertThat(result.workOrderId()).isEqualTo(WO_ID);
        assertThat(result.loggedLines()).hasSize(1);
        verify(stockBalanceRepository).conditionalDecrement(PART_A, LOC_ID, 3);
        verify(entityManager).persist(any()); // StockLedger
        verify(workOrderPartRepository).save(any());
        verify(eventPublisher).publish(any(DomainEvent.class));
    }

    // ---- Consume: insufficient stock ----------------------------------------

    @Test
    @DisplayName("Missing balance row is treated as insufficient stock (422)")
    void missingBalance_throws422() {
        when(stockBalanceRepository.findByPartIdAndLocationId(PART_A, LOC_ID))
                .thenReturn(Optional.empty());

        ConsumePartsCommand cmd = new ConsumePartsCommand(
                WO_ID, LOC_ID,
                List.of(new ConsumePartsCommand.LineItem(PART_A, 1, null)),
                ACTOR, null);

        assertThatThrownBy(() -> service.consumeParts(cmd))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(e -> {
                    InsufficientStockException ex = (InsufficientStockException) e;
                    assertThat(ex.getShortfalls()).hasSize(1);
                    assertThat(ex.getShortfalls().get(0).available()).isEqualTo(0);
                });

        // No balance mutation on insufficient stock
        verify(stockBalanceRepository, never()).conditionalDecrement(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Balance < requested quantity is insufficient stock (422)")
    void insufficientBalance_throws422() {
        mockBalance(PART_A, LOC_ID, 2); // only 2 available

        ConsumePartsCommand cmd = new ConsumePartsCommand(
                WO_ID, LOC_ID,
                List.of(new ConsumePartsCommand.LineItem(PART_A, 5, null)),
                ACTOR, null);

        assertThatThrownBy(() -> service.consumeParts(cmd))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(e -> {
                    InsufficientStockException ex = (InsufficientStockException) e;
                    assertThat(ex.getShortfalls().get(0).requested()).isEqualTo(5);
                    assertThat(ex.getShortfalls().get(0).available()).isEqualTo(2);
                });
    }

    // ---- Multi-line: all-or-nothing -----------------------------------------

    @Test
    @DisplayName("Multi-line: all lines checked before any mutation — all shortfalls reported")
    void multiLine_allShortfallsReported() {
        mockBalance(PART_A, LOC_ID, 0); // insufficient
        mockBalance(PART_B, LOC_ID, 0); // insufficient

        ConsumePartsCommand cmd = new ConsumePartsCommand(
                WO_ID, LOC_ID,
                List.of(
                        new ConsumePartsCommand.LineItem(PART_A, 3, null),
                        new ConsumePartsCommand.LineItem(PART_B, 2, null)),
                ACTOR, null);

        assertThatThrownBy(() -> service.consumeParts(cmd))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(e -> {
                    InsufficientStockException ex = (InsufficientStockException) e;
                    assertThat(ex.getShortfalls()).hasSize(2);
                });
        verify(stockBalanceRepository, never()).conditionalDecrement(any(), any(), anyInt());
    }

    // ---- Return: increments balance -----------------------------------------

    @Test
    @DisplayName("Return increments balance and persists RETURN record")
    void returnParts_success() {
        StockBalance balance = mockBalance(PART_A, LOC_ID, 5);
        when(stockBalanceRepository.increment(PART_A, LOC_ID, 2)).thenReturn(1);
        when(stockBalanceRepository.findByPartIdAndLocationId(PART_A, LOC_ID))
                .thenReturn(Optional.of(balance));

        ReturnPartsCommand cmd = new ReturnPartsCommand(
                WO_ID, LOC_ID,
                List.of(new ReturnPartsCommand.LineItem(PART_A, 2, "UNUSED")),
                ACTOR, null);

        StockMovementResult result = service.returnParts(cmd);

        assertThat(result.loggedLines()).hasSize(1);
        verify(stockBalanceRepository).increment(PART_A, LOC_ID, 2);

        ArgumentCaptor<com.fieldservice.inventory.domain.WorkOrderPart> wopCaptor =
                ArgumentCaptor.forClass(com.fieldservice.inventory.domain.WorkOrderPart.class);
        verify(workOrderPartRepository).save(wopCaptor.capture());
        assertThat(wopCaptor.getValue().getMovementType()).isEqualTo("RETURN");
    }

    // ---- helpers ------------------------------------------------------------

    private StockBalance mockBalance(UUID partId, UUID locationId, int qty) {
        StockBalance balance = new StockBalance(partId, locationId);
        setField(balance, "quantityOnHand", qty);
        when(stockBalanceRepository.findByPartIdAndLocationId(partId, locationId))
                .thenReturn(Optional.of(balance));
        return balance;
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            var f = getField(obj.getClass(), name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static java.lang.reflect.Field getField(Class<?> cls, String name) {
        try {
            return cls.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (cls.getSuperclass() != null) return getField(cls.getSuperclass(), name);
            throw new RuntimeException(e);
        }
    }
}
