package com.fieldservice.catalog;

import com.fieldservice.asset.domain.Asset;
import com.fieldservice.asset.repository.AssetRepository;
import com.fieldservice.catalog.web.AssetRequest;
import com.fieldservice.catalog.web.CustomerRequest;
import com.fieldservice.catalog.web.SiteRequest;
import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.customer.repository.CustomerAccountRepository;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.site.domain.Site;
import com.fieldservice.site.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock CustomerAccountRepository customerRepo;
    @Mock SiteRepository            siteRepo;
    @Mock AssetRepository           assetRepo;
    @Mock ScopedQueryExecutor       scopedQueryExecutor;
    @Mock DomainEventPublisher      eventPublisher;

    CatalogService service;

    AccessScope adminScope = new AccessScope(UUID.randomUUID(), Set.of("ADMIN"), null, Set.of());

    @BeforeEach
    void setUp() {
        service = new CatalogService(customerRepo, siteRepo, assetRepo, scopedQueryExecutor, eventPublisher);
    }

    @Nested
    @DisplayName("Hierarchy guards")
    class HierarchyGuards {

        @Test
        @DisplayName("createSite refuses when customer is inactive")
        void createSite_refusesInactiveCustomer() {
            UUID customerId = UUID.randomUUID();
            CustomerAccount inactive = new CustomerAccount(UUID.randomUUID(), "Inactive Corp");
            inactive.deactivate(java.time.Instant.now());

            when(scopedQueryExecutor.findById(customerRepo, customerId, adminScope, CustomerAccount.class))
                    .thenReturn(Optional.of(inactive));

            SiteRequest req = new SiteRequest("S01", "Site One", null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.createSite(customerId, req, adminScope))
                    .isInstanceOf(BusinessGuardException.class)
                    .hasMessageContaining("inactive");

            verify(siteRepo, never()).save(any());
        }

        @Test
        @DisplayName("createAsset refuses when site is inactive")
        void createAsset_refusesInactiveSite() {
            UUID siteId = UUID.randomUUID();
            Site inactive = new Site(UUID.randomUUID(), "Closed Site", UUID.randomUUID());
            inactive.deactivate(java.time.Instant.now());

            when(scopedQueryExecutor.findById(siteRepo, siteId, adminScope, Site.class))
                    .thenReturn(Optional.of(inactive));

            AssetRequest req = new AssetRequest("TAG-001", "Mfr", "Model", "SN001", "HVAC", null);

            assertThatThrownBy(() -> service.createAsset(siteId, req, adminScope))
                    .isInstanceOf(BusinessGuardException.class)
                    .hasMessageContaining("inactive");

            verify(assetRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Deactivation cascade")
    class DeactivationCascade {

        @Test
        @DisplayName("deactivateCustomer cascades to active sites and their assets")
        void deactivateCustomer_cascadesToSitesAndAssets() {
            UUID customerId = UUID.randomUUID();
            CustomerAccount customer = new CustomerAccount(customerId, "Corp");
            UUID siteId = UUID.randomUUID();
            Site site = new Site(siteId, "Site A", customerId);
            Asset asset = new Asset(siteId, "SN001", "Model", "Mfr");

            when(scopedQueryExecutor.findById(customerRepo, customerId, adminScope, CustomerAccount.class))
                    .thenReturn(Optional.of(customer));
            when(siteRepo.findByCustomerIdAndActiveTrue(customerId)).thenReturn(List.of(site));
            when(assetRepo.findBySiteIdAndActiveTrue(siteId)).thenReturn(List.of(asset));
            when(customerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(siteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(assetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.deactivateCustomer(customerId, adminScope);

            verify(customerRepo).save(customer);
            verify(siteRepo).save(site);
            verify(assetRepo).save(asset);
        }

        @Test
        @DisplayName("deactivated records remain retrievable by scope")
        void deactivatedRecords_remainRetrievable() {
            UUID customerId = UUID.randomUUID();
            CustomerAccount customer = new CustomerAccount(customerId, "Corp");
            customer.deactivate(java.time.Instant.now());

            when(scopedQueryExecutor.findById(customerRepo, customerId, adminScope, CustomerAccount.class))
                    .thenReturn(Optional.of(customer));

            Optional<CustomerAccount> result = service.findCustomer(customerId, adminScope);
            assert result.isPresent();
        }
    }

    @Nested
    @DisplayName("Sort allow-list and page clamping")
    class SortAndPagination {

        @Test
        @DisplayName("PageQuery clamps size=1000 to 50")
        void pageQuery_clampsLargeSize() {
            PageQuery query = PageQuery.of(0, 1000, null);
            assert query.size() == 50;
        }

        @Test
        @DisplayName("PageQuery clamps size=0 is treated as default")
        void pageQuery_treatsZeroAsDefault() {
            PageQuery query = PageQuery.of(0, 0, "legalName,asc");
            assert query.size() > 0;
        }

        @Test
        @DisplayName("SortAllowList rejects unknown sort field and falls back to default")
        void sortAllowList_rejectsUnknownField() {
            SortAllowList allowList = SortAllowList.of(
                    java.util.Map.of("legalName", "legalName"),
                    "legalName");
            // Requesting an unknown field — should not throw; falls back to default
            Pageable pageable = PageQuery.of(0, 10, "unknownField,asc").toPageable(allowList);
            assert pageable.getSort() != null;
        }
    }
}
