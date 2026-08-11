package com.fieldservice.catalog;

import com.fieldservice.asset.domain.Asset;
import com.fieldservice.asset.repository.AssetRepository;
import com.fieldservice.catalog.event.AssetChanged;
import com.fieldservice.catalog.event.CustomerChanged;
import com.fieldservice.catalog.event.SiteChanged;
import com.fieldservice.catalog.web.AssetRequest;
import com.fieldservice.catalog.web.CustomerRequest;
import com.fieldservice.catalog.web.SiteRequest;
import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.customer.repository.CustomerAccountRepository;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.ConflictException;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.site.domain.Site;
import com.fieldservice.site.repository.SiteRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for the catalog module (customer, site, asset).
 *
 * <p>All public methods carry {@code @PreAuthorize} and run within a transaction.
 * Hierarchy guards enforce active parent existence before creating a child.
 * Deactivation replaces deletion for all referenced records, cascading down the hierarchy.
 */
@Service
@Transactional
public class CatalogService {

    private final CustomerAccountRepository customerRepo;
    private final SiteRepository            siteRepo;
    private final AssetRepository           assetRepo;
    private final ScopedQueryExecutor       scopedQueryExecutor;
    private final DomainEventPublisher      eventPublisher;

    public CatalogService(CustomerAccountRepository customerRepo,
                          SiteRepository siteRepo,
                          AssetRepository assetRepo,
                          ScopedQueryExecutor scopedQueryExecutor,
                          DomainEventPublisher eventPublisher) {
        this.customerRepo       = customerRepo;
        this.siteRepo           = siteRepo;
        this.assetRepo          = assetRepo;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.eventPublisher     = eventPublisher;
    }

    // ---- Customer operations ------------------------------------------------

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER')")
    @Transactional(readOnly = true)
    public Page<CustomerAccount> findCustomers(Specification<CustomerAccount> filter,
                                               Pageable pageable,
                                               AccessScope scope) {
        return scopedQueryExecutor.findAll(customerRepo, filter, pageable, scope, CustomerAccount.class);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER')")
    @Transactional(readOnly = true)
    public Optional<CustomerAccount> findCustomer(UUID id, AccessScope scope) {
        return scopedQueryExecutor.findById(customerRepo, id, scope, CustomerAccount.class);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public CustomerAccount createCustomer(CustomerRequest request) {
        String legalName = request.legalName() != null ? request.legalName() : request.accountCode();
        CustomerAccount customer = new CustomerAccount(request.accountCode(), legalName);
        applyCustomerRequest(customer, request);
        try {
            CustomerAccount saved = customerRepo.save(customer);
            publishCustomerEvent(saved, "CREATED");
            return saved;
        } catch (Exception ex) {
            if (isDuplicateKey(ex)) {
                throw new ConflictException("account_code already exists: " + request.accountCode());
            }
            throw ex;
        }
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public void deactivateCustomer(UUID id, AccessScope scope) {
        CustomerAccount customer = scopedQueryExecutor
                .findById(customerRepo, id, scope, CustomerAccount.class)
                .orElseThrow(() -> new com.fieldservice.platform.api.exception.NotFoundException("Customer", id.toString()));

        Instant now = Instant.now();
        customer.deactivate(now);
        customerRepo.save(customer);
        cascadeDeactivateSites(customer.getId(), now);
        publishCustomerEvent(customer, "DEACTIVATED");
    }

    // ---- Site operations ----------------------------------------------------

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER', 'TECHNICIAN')")
    @Transactional(readOnly = true)
    public Page<Site> findSitesByCustomer(UUID customerId, Specification<Site> filter,
                                          Pageable pageable, AccessScope scope) {
        Specification<Site> customerFilter = (root, q, cb) -> cb.equal(root.get("customerId"), customerId);
        Specification<Site> combined = filter == null ? customerFilter : customerFilter.and(filter);
        return scopedQueryExecutor.findAll(siteRepo, combined, pageable, scope, Site.class);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER', 'TECHNICIAN')")
    @Transactional(readOnly = true)
    public Optional<Site> findSite(UUID id, AccessScope scope) {
        return scopedQueryExecutor.findById(siteRepo, id, scope, Site.class);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public Site createSite(UUID customerId, SiteRequest request, AccessScope scope) {
        CustomerAccount customer = scopedQueryExecutor
                .findById(customerRepo, customerId, scope, CustomerAccount.class)
                .orElseThrow(() -> new com.fieldservice.platform.api.exception.NotFoundException("Customer", customerId.toString()));

        if (!customer.isActive()) {
            throw new BusinessGuardException("INACTIVE_PARENT",
                    "Cannot create site under inactive customer: " + customerId);
        }

        String displayName = request.displayName() != null ? request.displayName() : request.siteCode();
        Site site = new Site(request.siteCode(), displayName, customerId);
        applySiteRequest(site, request);
        try {
            Site saved = siteRepo.save(site);
            publishSiteEvent(saved, "CREATED");
            return saved;
        } catch (Exception ex) {
            if (isDuplicateKey(ex)) {
                throw new ConflictException("site_code already exists under customer: " + request.siteCode());
            }
            throw ex;
        }
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public void deactivateSite(UUID id, AccessScope scope) {
        Site site = scopedQueryExecutor
                .findById(siteRepo, id, scope, Site.class)
                .orElseThrow(() -> new com.fieldservice.platform.api.exception.NotFoundException("Site", id.toString()));

        Instant now = Instant.now();
        site.deactivate(now);
        siteRepo.save(site);
        cascadeDeactivateAssets(site.getId(), now);
        publishSiteEvent(site, "DEACTIVATED");
    }

    // ---- Asset operations ---------------------------------------------------

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER', 'TECHNICIAN')")
    @Transactional(readOnly = true)
    public Page<Asset> findAssetsBySite(UUID siteId, Specification<Asset> filter,
                                        Pageable pageable, AccessScope scope) {
        Specification<Asset> siteFilter = (root, q, cb) -> cb.equal(root.get("siteId"), siteId);
        Specification<Asset> combined = filter == null ? siteFilter : siteFilter.and(filter);
        return scopedQueryExecutor.findAll(assetRepo, combined, pageable, scope, Asset.class);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER', 'TECHNICIAN')")
    @Transactional(readOnly = true)
    public Optional<Asset> findAsset(UUID id, AccessScope scope) {
        return scopedQueryExecutor.findById(assetRepo, id, scope, Asset.class);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public Asset createAsset(UUID siteId, AssetRequest request, AccessScope scope) {
        Site site = scopedQueryExecutor
                .findById(siteRepo, siteId, scope, Site.class)
                .orElseThrow(() -> new com.fieldservice.platform.api.exception.NotFoundException("Site", siteId.toString()));

        if (!site.isActive()) {
            throw new BusinessGuardException("INACTIVE_PARENT",
                    "Cannot create asset under inactive site: " + siteId);
        }

        Asset asset = new Asset(siteId, request.serialNumber(), request.model(), request.manufacturer());
        applyAssetRequest(asset, request);
        try {
            Asset saved = assetRepo.save(asset);
            publishAssetEvent(saved, "CREATED");
            return saved;
        } catch (Exception ex) {
            if (isDuplicateKey(ex)) {
                throw new ConflictException("asset_tag already exists under site: " + request.assetTag());
            }
            throw ex;
        }
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public void deactivateAsset(UUID id, AccessScope scope) {
        Asset asset = scopedQueryExecutor
                .findById(assetRepo, id, scope, Asset.class)
                .orElseThrow(() -> new com.fieldservice.platform.api.exception.NotFoundException("Asset", id.toString()));

        Instant now = Instant.now();
        asset.deactivate(now);
        assetRepo.save(asset);
        publishAssetEvent(asset, "DEACTIVATED");
    }

    // ---- Internal helpers ---------------------------------------------------

    private void applyCustomerRequest(CustomerAccount c, CustomerRequest req) {
        c.update(req.legalName(), req.primaryContactName(), req.primaryContactEmail(),
                 req.primaryContactPhone(), req.billingAddress(), null);
    }

    private void applySiteRequest(Site s, SiteRequest req) {
        s.update(req.displayName(), req.addressLine1(), req.addressLine2(),
                 req.city(), req.postcode(), req.latitude(), req.longitude(),
                 req.accessNotes(), null);
    }

    private void applyAssetRequest(Asset a, AssetRequest req) {
        a.update(req.assetTag(), req.manufacturer(), req.model(),
                 req.serialNumber(), req.category(), req.installedAt(), null);
    }

    private void cascadeDeactivateSites(UUID customerId, Instant now) {
        List<Site> activeSites = siteRepo.findByCustomerIdAndActiveTrue(customerId);
        for (Site site : activeSites) {
            site.deactivate(now);
            siteRepo.save(site);
            cascadeDeactivateAssets(site.getId(), now);
            publishSiteEvent(site, "DEACTIVATED");
        }
    }

    private void cascadeDeactivateAssets(UUID siteId, Instant now) {
        List<Asset> activeAssets = assetRepo.findBySiteIdAndActiveTrue(siteId);
        for (Asset asset : activeAssets) {
            asset.deactivate(now);
            assetRepo.save(asset);
            publishAssetEvent(asset, "DEACTIVATED");
        }
    }

    private void publishCustomerEvent(CustomerAccount c, String changeType) {
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "CUSTOMER_CHANGED",
                "CUSTOMER",
                c.getId(),
                Instant.now(),
                null, null,
                new CustomerChanged(c.getId(), c.getAccountCode(), c.getLegalName(), c.isActive(), changeType)
        ));
    }

    private void publishSiteEvent(Site s, String changeType) {
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "SITE_CHANGED",
                "SITE",
                s.getId(),
                Instant.now(),
                null, null,
                new SiteChanged(s.getId(), s.getCustomerId(), s.getSiteCode(), s.isActive(), changeType)
        ));
    }

    private void publishAssetEvent(Asset a, String changeType) {
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "ASSET_CHANGED",
                "ASSET",
                a.getId(),
                Instant.now(),
                null, null,
                new AssetChanged(a.getId(), a.getSiteId(), a.getAssetTag(), a.isActive(), changeType)
        ));
    }

    private boolean isDuplicateKey(Exception ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (cause instanceof java.sql.SQLException sqle) {
                // PostgreSQL unique violation code
                return "23505".equals(sqle.getSQLState());
            }
            cause = cause.getCause();
        }
        return false;
    }

}
