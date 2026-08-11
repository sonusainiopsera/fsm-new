package com.fieldservice.catalog;

import com.fieldservice.catalog.application.CatalogService;
import com.fieldservice.catalog.application.CreateAssetCommand;
import com.fieldservice.catalog.application.CreateCustomerCommand;
import com.fieldservice.catalog.application.CreateSiteCommand;
import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.asset.AssetRepository;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.customer.CustomerRepository;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.site.SiteRepository;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalogService} — no Spring context.
 *
 * <p>Covers: hierarchy guards (inactive parent), cascade deactivation,
 * idempotency of deactivation, sort allow-list resolution, sort injection
 * rejection, and PageQuery size clamping.
 */
@DisplayName("CatalogService unit tests (WO-117)")
class CatalogServiceUnitTest {

    // ── mocks ────────────────────────────────────────────────────────────────

    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final SiteRepository     siteRepository     = mock(SiteRepository.class);
    private final AssetRepository    assetRepository    = mock(AssetRepository.class);
    private final ScopedQueryExecutor scopedExecutor    = mock(ScopedQueryExecutor.class);
    private final DomainEventPublisher eventPublisher   = mock(DomainEventPublisher.class);
    private final AccessScopeResolver  scopeResolver    = mock(AccessScopeResolver.class);

    private CatalogService service;

    // ── fixture IDs ──────────────────────────────────────────────────────────

    private static final UUID CUSTOMER_ID = UUID.fromString("cc000000-0000-0000-0000-000000000001");
    private static final UUID SITE_ID     = UUID.fromString("dd000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID    = UUID.fromString("ee000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new CatalogService(
                customerRepository, siteRepository, assetRepository,
                scopedExecutor, eventPublisher, scopeResolver);
        // resolveActorId() catches exceptions and returns null — simulate no auth context
        when(scopeResolver.resolve()).thenThrow(new IllegalStateException("no security context in unit test"));
    }

    // =========================================================================
    // Hierarchy guards
    // =========================================================================

    @Nested
    @DisplayName("Hierarchy guard — INACTIVE_PARENT")
    class HierarchyGuard {

        @Test
        @DisplayName("createSite under inactive customer throws BusinessGuardException")
        void createSite_inactiveCustomer_throwsGuard() {
            Customer inactiveCustomer = makeCustomer(CUSTOMER_ID, false);
            when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(inactiveCustomer));

            CreateSiteCommand cmd = new CreateSiteCommand(
                    "S-001", "Test Site", "1 Test St", "TS1 1AA", null, null, null);

            assertThatThrownBy(() -> service.createSite(CUSTOMER_ID, cmd))
                    .isInstanceOf(BusinessGuardException.class)
                    .satisfies(ex -> assertThat(((BusinessGuardException) ex).getGuardName())
                            .isEqualTo("INACTIVE_PARENT"));

            verify(siteRepository, never()).save(any());
            verify(eventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("createAsset under inactive site throws BusinessGuardException")
        void createAsset_inactiveSite_throwsGuard() {
            Site inactiveSite = makeSite(SITE_ID, CUSTOMER_ID, false);
            when(siteRepository.findById(SITE_ID)).thenReturn(Optional.of(inactiveSite));

            CreateAssetCommand cmd = new CreateAssetCommand(
                    "TAG-001", "Carrier", "AquaForce", "SN-001", "HVAC", null);

            assertThatThrownBy(() -> service.createAsset(SITE_ID, cmd))
                    .isInstanceOf(BusinessGuardException.class)
                    .satisfies(ex -> assertThat(((BusinessGuardException) ex).getGuardName())
                            .isEqualTo("INACTIVE_PARENT"));

            verify(assetRepository, never()).save(any());
            verify(eventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("createSite under active customer proceeds without throwing")
        void createSite_activeCustomer_doesNotThrow() {
            Customer activeCustomer = makeCustomer(CUSTOMER_ID, true);
            when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(activeCustomer));

            Site savedSite = makeSite(SITE_ID, CUSTOMER_ID, true);
            when(siteRepository.save(any(Site.class))).thenReturn(savedSite);

            CreateSiteCommand cmd = new CreateSiteCommand(
                    "S-001", "Test Site", "1 Test St", "TS1 1AA", null, null, null);

            var ref = service.createSite(CUSTOMER_ID, cmd);

            assertThat(ref.id()).isEqualTo(SITE_ID);
            verify(siteRepository, times(1)).save(any(Site.class));
        }

        @Test
        @DisplayName("createSite with missing customer throws NotFoundException")
        void createSite_unknownCustomer_throwsNotFound() {
            when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

            CreateSiteCommand cmd = new CreateSiteCommand(
                    "S-001", "Test Site", "1 Test St", "TS1 1AA", null, null, null);

            assertThatThrownBy(() -> service.createSite(CUSTOMER_ID, cmd))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // =========================================================================
    // Deactivation — cascade and idempotency
    // =========================================================================

    @Nested
    @DisplayName("Deactivation cascade and idempotency")
    class Deactivation {

        @Test
        @DisplayName("deactivateCustomer cascades to sites and assets")
        void deactivateCustomer_cascadesToSitesAndAssets() {
            Customer customer = makeCustomer(CUSTOMER_ID, true);
            when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

            Site site = makeSite(SITE_ID, CUSTOMER_ID, true);
            when(siteRepository.findAll(any(Specification.class))).thenReturn(List.of(site));
            when(siteRepository.save(any(Site.class))).thenReturn(site);

            Asset asset = makeAsset(ASSET_ID, SITE_ID, true);
            when(assetRepository.findAll(any(Specification.class))).thenReturn(List.of(asset));
            when(assetRepository.save(any(Asset.class))).thenReturn(asset);

            when(customerRepository.save(any(Customer.class))).thenReturn(customer);

            service.deactivateCustomer(CUSTOMER_ID);

            assertThat(customer.isActive()).isFalse();
            assertThat(customer.getDeactivatedAt()).isNotNull();

            assertThat(site.isActive()).isFalse();
            assertThat(site.getDeactivatedAt()).isNotNull();

            assertThat(asset.isActive()).isFalse();
            assertThat(asset.getDeactivatedAt()).isNotNull();

            verify(customerRepository).save(customer);
            verify(siteRepository).save(site);
            verify(assetRepository).save(asset);
            // 3 publish calls: customer + site + asset
            verify(eventPublisher, times(3)).publish(any());
        }

        @Test
        @DisplayName("deactivateCustomer is idempotent when already inactive")
        void deactivateCustomer_alreadyInactive_noOp() {
            Customer customer = makeCustomer(CUSTOMER_ID, false);
            when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

            service.deactivateCustomer(CUSTOMER_ID);

            verify(customerRepository, never()).save(any());
            verify(eventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("deactivateSite is idempotent when already inactive")
        void deactivateSite_alreadyInactive_noOp() {
            Site site = makeSite(SITE_ID, CUSTOMER_ID, false);
            when(siteRepository.findById(SITE_ID)).thenReturn(Optional.of(site));

            service.deactivateSite(SITE_ID);

            verify(siteRepository, never()).save(any());
            verify(eventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("deactivateAsset is idempotent when already inactive")
        void deactivateAsset_alreadyInactive_noOp() {
            Asset asset = makeAsset(ASSET_ID, SITE_ID, false);
            when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));

            service.deactivateAsset(ASSET_ID);

            verify(assetRepository, never()).save(any());
            verify(eventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("deactivateSite cascades to assets only (not customer)")
        void deactivateSite_cascadesToAssetsOnly() {
            Site site = makeSite(SITE_ID, CUSTOMER_ID, true);
            when(siteRepository.findById(SITE_ID)).thenReturn(Optional.of(site));
            when(siteRepository.save(any(Site.class))).thenReturn(site);

            Asset asset = makeAsset(ASSET_ID, SITE_ID, true);
            when(assetRepository.findAll(any(Specification.class))).thenReturn(List.of(asset));
            when(assetRepository.save(any(Asset.class))).thenReturn(asset);

            service.deactivateSite(SITE_ID);

            assertThat(site.isActive()).isFalse();
            assertThat(asset.isActive()).isFalse();
            verify(customerRepository, never()).save(any());
            // 2 events: site + asset
            verify(eventPublisher, times(2)).publish(any());
        }
    }

    // =========================================================================
    // Sort allow-list
    // =========================================================================

    @Nested
    @DisplayName("Sort allow-list")
    class SortAllowListTests {

        @Test
        @DisplayName("CUSTOMER_SORTS resolves all expected public field names")
        void customerSorts_knownFields_resolve() {
            SortAllowList sorts = CatalogService.CUSTOMER_SORTS;
            assertThat(sorts.resolvePersistentName("accountCode")).isEqualTo("accountCode");
            assertThat(sorts.resolvePersistentName("legalName")).isEqualTo("legalName");
            assertThat(sorts.resolvePersistentName("active")).isEqualTo("active");
            assertThat(sorts.resolvePersistentName("createdAt")).isEqualTo("createdAt");
        }

        @Test
        @DisplayName("SITE_SORTS resolves all expected public field names")
        void siteSorts_knownFields_resolve() {
            SortAllowList sorts = CatalogService.SITE_SORTS;
            assertThat(sorts.resolvePersistentName("siteCode")).isEqualTo("siteCode");
            assertThat(sorts.resolvePersistentName("displayName")).isEqualTo("displayName");
            assertThat(sorts.resolvePersistentName("active")).isEqualTo("active");
            assertThat(sorts.resolvePersistentName("createdAt")).isEqualTo("createdAt");
        }

        @Test
        @DisplayName("ASSET_SORTS resolves all expected public field names")
        void assetSorts_knownFields_resolve() {
            SortAllowList sorts = CatalogService.ASSET_SORTS;
            assertThat(sorts.resolvePersistentName("assetTag")).isEqualTo("assetTag");
            assertThat(sorts.resolvePersistentName("category")).isEqualTo("category");
            assertThat(sorts.resolvePersistentName("active")).isEqualTo("active");
            assertThat(sorts.resolvePersistentName("createdAt")).isEqualTo("createdAt");
        }

        @Test
        @DisplayName("CUSTOMER_SORTS rejects unknown field name with InvalidSortException")
        void customerSorts_unknownField_throwsInvalidSort() {
            assertThatThrownBy(() -> CatalogService.CUSTOMER_SORTS.resolvePersistentName("password"))
                    .isInstanceOf(InvalidSortException.class);
        }

        @Test
        @DisplayName("SITE_SORTS rejects SQL-injection attempt in field name")
        void siteSorts_injectionAttempt_rejected() {
            assertThatThrownBy(() -> CatalogService.SITE_SORTS.resolvePersistentName("1 OR 1=1"))
                    .isInstanceOf(InvalidSortException.class);
        }

        @Test
        @DisplayName("ASSET_SORTS rejects unknown field name")
        void assetSorts_unknownField_throwsInvalidSort() {
            assertThatThrownBy(() -> CatalogService.ASSET_SORTS.resolvePersistentName("price"))
                    .isInstanceOf(InvalidSortException.class);
        }
    }

    // =========================================================================
    // PageQuery clamping
    // =========================================================================

    @Nested
    @DisplayName("PageQuery size clamping")
    class PageQueryClamping {

        @Test
        @DisplayName("Size above MAX_SIZE is clamped to MAX_SIZE=50")
        void size_aboveMax_clampedTo50() {
            PageQuery q = new PageQuery(0, 9999, List.of(), null);
            assertThat(q.size()).isEqualTo(PageQuery.MAX_SIZE);
        }

        @Test
        @DisplayName("Size of zero is clamped to 1")
        void size_zero_clampedToOne() {
            PageQuery q = new PageQuery(0, 0, List.of(), null);
            assertThat(q.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Negative size is clamped to 1")
        void size_negative_clampedToOne() {
            PageQuery q = new PageQuery(0, -10, List.of(), null);
            assertThat(q.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Negative page is clamped to 0")
        void page_negative_clampedToZero() {
            PageQuery q = new PageQuery(-5, 20, List.of(), null);
            assertThat(q.page()).isEqualTo(0);
        }

        @Test
        @DisplayName("Valid size within bounds is preserved")
        void size_withinBounds_preserved() {
            PageQuery q = new PageQuery(2, 25, List.of(), null);
            assertThat(q.size()).isEqualTo(25);
            assertThat(q.page()).isEqualTo(2);
        }
    }

    // =========================================================================
    // Test fixture helpers
    // =========================================================================

    private static Customer makeCustomer(UUID id, boolean active) {
        Customer c = new Customer();
        c.setId(id);
        c.setName("Test Customer");
        c.setLegalName("Test Customer Ltd");
        c.setAccountCode("TC-001");
        c.setActive(active);
        return c;
    }

    private static Site makeSite(UUID id, UUID customerId, boolean active) {
        Customer customer = makeCustomer(customerId, true);
        Site s = new Site();
        s.setId(id);
        s.setCustomer(customer);
        s.setName("Test Site");
        s.setSiteCode("S-001");
        s.setDisplayName("Test Site Display");
        s.setActive(active);
        return s;
    }

    private static Asset makeAsset(UUID id, UUID siteId, boolean active) {
        Customer customer = makeCustomer(UUID.randomUUID(), true);
        Site site = makeSite(siteId, customer.getId(), true);
        Asset a = new Asset();
        a.setId(id);
        a.setSite(site);
        a.setName("Test Asset");
        a.setAssetTag("TAG-001");
        a.setCategory("HVAC");
        a.setActive(active);
        return a;
    }
}
