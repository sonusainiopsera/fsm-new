package com.fieldservice.catalog.application;

import com.fieldservice.catalog.api.AssetRef;
import com.fieldservice.catalog.api.CatalogQueryPort;
import com.fieldservice.catalog.api.CustomerRef;
import com.fieldservice.catalog.api.SiteRef;
import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.asset.AssetRepository;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.customer.CustomerRepository;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.site.SiteRepository;
import com.fieldservice.outbox.payload.AssetChangedPayload;
import com.fieldservice.outbox.payload.CustomerChangedPayload;
import com.fieldservice.outbox.payload.SiteChangedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.ConflictException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core catalog service: CRUD, hierarchy guards, deactivation, and outbox publication
 * for the customer → site → asset aggregate hierarchy.
 *
 * <p><strong>Hierarchy invariant:</strong> creating a child under an inactive parent
 * is refused with {@link BusinessGuardException} (→ HTTP 422).
 *
 * <p><strong>Deactivation semantics:</strong> a DELETE-style call sets {@code active=false}
 * and {@code deactivated_at}. Child records are cascade-deactivated within the same
 * transaction. Physical deletion is reserved for the GDPR erasure path only.
 *
 * <p><strong>Outbox:</strong> CustomerChanged, SiteChanged, AssetChanged events are
 * published atomically with the domain row via {@link DomainEventPublisher}
 * ({@code MANDATORY} propagation).
 *
 * <p><strong>Row scoping:</strong> all reads are routed through {@link ScopedQueryExecutor}
 * so the AccessScope predicate cannot be bypassed.
 */
@Service
@Transactional
public class CatalogService implements CatalogQueryPort {

    // ── Sort allow-lists per entity ─────────────────────────────────────────

    static final SortAllowList CUSTOMER_SORTS = SortAllowList.of(
            "accountCode", "accountCode",
            "legalName",   "legalName",
            "active",      "active",
            "createdAt",   "createdAt"
    );

    static final SortAllowList SITE_SORTS = SortAllowList.of(
            "siteCode",    "siteCode",
            "displayName", "displayName",
            "active",      "active",
            "createdAt",   "createdAt"
    );

    static final SortAllowList ASSET_SORTS = SortAllowList.of(
            "assetTag",    "assetTag",
            "category",    "category",
            "active",      "active",
            "createdAt",   "createdAt"
    );

    // ── Dependencies ────────────────────────────────────────────────────────

    private final CustomerRepository customerRepository;
    private final SiteRepository siteRepository;
    private final AssetRepository assetRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final DomainEventPublisher eventPublisher;
    private final AccessScopeResolver scopeResolver;

    public CatalogService(
            CustomerRepository customerRepository,
            SiteRepository siteRepository,
            AssetRepository assetRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            DomainEventPublisher eventPublisher,
            AccessScopeResolver scopeResolver) {
        this.customerRepository = customerRepository;
        this.siteRepository = siteRepository;
        this.assetRepository = assetRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.eventPublisher = eventPublisher;
        this.scopeResolver = scopeResolver;
    }

    // =========================================================================
    // CatalogQueryPort — cross-module read API
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Optional<CustomerRef> findCustomerById(UUID customerId) {
        return scopedQueryExecutor.findById(Customer.class, customerId, customerRepository)
                .map(this::toCustomerRef);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Optional<SiteRef> findSiteById(UUID siteId) {
        return scopedQueryExecutor.findById(Site.class, siteId, siteRepository)
                .map(this::toSiteRef);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Optional<AssetRef> findAssetById(UUID assetId) {
        return scopedQueryExecutor.findById(Asset.class, assetId, assetRepository)
                .map(this::toAssetRef);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public boolean isCustomerActive(UUID customerId) {
        return scopedQueryExecutor.findById(Customer.class, customerId, customerRepository)
                .map(Customer::isActive)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public boolean isSiteActive(UUID siteId) {
        return scopedQueryExecutor.findById(Site.class, siteId, siteRepository)
                .map(Site::isActive)
                .orElse(false);
    }

    // =========================================================================
    // Customer CRUD
    // =========================================================================

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    @Transactional(readOnly = true)
    public PagedResponse<CustomerRef> listCustomers(
            String q, PageQuery pageQuery, HttpServletRequest request) {
        Specification<Customer> filter = buildCustomerFilter(q);
        Sort sort = buildSort(CUSTOMER_SORTS, pageQuery, "legalName");
        PageRequest pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);
        Page<Customer> page = scopedQueryExecutor.findAll(Customer.class, filter, pageable, customerRepository);
        return toPagedResponse(page.map(this::toCustomerRef), request);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    @Transactional(readOnly = true)
    public CustomerRef getCustomer(UUID id) {
        return scopedQueryExecutor.findById(Customer.class, id, customerRepository)
                .map(this::toCustomerRef)
                .orElseThrow(() -> new NotFoundException("Customer", id));
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public CustomerRef createCustomer(CreateCustomerCommand cmd) {
        Customer customer = new Customer();
        customer.setName(cmd.legalName());
        customer.setAccountCode(cmd.accountCode());
        customer.setLegalName(cmd.legalName());
        customer.setPrimaryContactName(cmd.primaryContactName());
        customer.setPrimaryContactEmail(cmd.primaryContactEmail());
        customer.setPrimaryContactPhone(cmd.primaryContactPhone());
        customer.setBillingAddress(cmd.billingAddress());
        Customer saved = customerRepository.save(customer);
        publishCustomerEvent(saved, "CREATED");
        return toCustomerRef(saved);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public CustomerRef updateCustomer(UUID id, CreateCustomerCommand cmd) {
        Customer customer = loadCustomerForWrite(id);
        customer.setName(cmd.legalName());
        customer.setAccountCode(cmd.accountCode());
        customer.setLegalName(cmd.legalName());
        customer.setPrimaryContactName(cmd.primaryContactName());
        customer.setPrimaryContactEmail(cmd.primaryContactEmail());
        customer.setPrimaryContactPhone(cmd.primaryContactPhone());
        customer.setBillingAddress(cmd.billingAddress());
        Customer saved = customerRepository.save(customer);
        publishCustomerEvent(saved, "UPDATED");
        return toCustomerRef(saved);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public void deactivateCustomer(UUID id) {
        Customer customer = loadCustomerForWrite(id);
        if (!customer.isActive()) return; // idempotent
        customer.setActive(false);
        customer.setDeactivatedAt(Instant.now());
        // Cascade deactivate all active sites and their assets
        deactivateSitesForCustomer(customer);
        customerRepository.save(customer);
        publishCustomerEvent(customer, "DEACTIVATED");
    }

    // =========================================================================
    // Site CRUD
    // =========================================================================

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER')")
    @Transactional(readOnly = true)
    public PagedResponse<SiteRef> listSitesForCustomer(
            UUID customerId, String q, PageQuery pageQuery, HttpServletRequest request) {
        Specification<Site> filter = buildSiteFilter(customerId, q);
        Sort sort = buildSort(SITE_SORTS, pageQuery, "displayName");
        PageRequest pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);
        Page<Site> page = scopedQueryExecutor.findAll(Site.class, filter, pageable, siteRepository);
        return toPagedResponse(page.map(this::toSiteRef), request);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER')")
    @Transactional(readOnly = true)
    public SiteRef getSite(UUID id) {
        return scopedQueryExecutor.findById(Site.class, id, siteRepository)
                .map(this::toSiteRef)
                .orElseThrow(() -> new NotFoundException("Site", id));
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public SiteRef createSite(UUID customerId, CreateSiteCommand cmd) {
        Customer customer = loadCustomerForWrite(customerId);
        if (!customer.isActive()) {
            throw new BusinessGuardException("INACTIVE_PARENT",
                    "Cannot create site under inactive customer " + customerId);
        }
        Site site = new Site();
        site.setCustomer(customer);
        site.setName(cmd.displayName());
        site.setSiteCode(cmd.siteCode());
        site.setDisplayName(cmd.displayName());
        site.setAddress(cmd.address());
        site.setPostcode(cmd.postcode());
        site.setAccessNotes(cmd.accessNotes());
        if (cmd.latitude() != null) site.setLatitude(cmd.latitude());
        if (cmd.longitude() != null) site.setLongitude(cmd.longitude());
        Site saved = siteRepository.save(site);
        publishSiteEvent(saved, "CREATED");
        return toSiteRef(saved);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public void deactivateSite(UUID id) {
        Site site = loadSiteForWrite(id);
        if (!site.isActive()) return;
        site.setActive(false);
        site.setDeactivatedAt(Instant.now());
        deactivateAssetsForSite(site);
        siteRepository.save(site);
        publishSiteEvent(site, "DEACTIVATED");
    }

    // =========================================================================
    // Asset CRUD
    // =========================================================================

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    @Transactional(readOnly = true)
    public PagedResponse<AssetRef> listAssetsForSite(
            UUID siteId, String q, PageQuery pageQuery, HttpServletRequest request) {
        Specification<Asset> filter = buildAssetFilter(siteId, q);
        Sort sort = buildSort(ASSET_SORTS, pageQuery, "assetTag");
        PageRequest pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);
        Page<Asset> page = scopedQueryExecutor.findAll(Asset.class, filter, pageable, assetRepository);
        return toPagedResponse(page.map(this::toAssetRef), request);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN', 'CUSTOMER')")
    @Transactional(readOnly = true)
    public AssetRef getAsset(UUID id) {
        return scopedQueryExecutor.findById(Asset.class, id, assetRepository)
                .map(this::toAssetRef)
                .orElseThrow(() -> new NotFoundException("Asset", id));
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public AssetRef createAsset(UUID siteId, CreateAssetCommand cmd) {
        Site site = loadSiteForWrite(siteId);
        if (!site.isActive()) {
            throw new BusinessGuardException("INACTIVE_PARENT",
                    "Cannot create asset under inactive site " + siteId);
        }
        Asset asset = new Asset();
        asset.setSite(site);
        asset.setName(cmd.assetTag() != null ? cmd.assetTag() : "Asset");
        asset.setAssetTag(cmd.assetTag());
        asset.setManufacturer(cmd.manufacturer());
        asset.setModel(cmd.model());
        asset.setCategory(cmd.category());
        asset.setSerialNo(cmd.serialNumber());
        asset.setInstalledOn(cmd.installedOn());
        Asset saved = assetRepository.save(asset);
        publishAssetEvent(saved, "CREATED");
        return toAssetRef(saved);
    }

    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public void deactivateAsset(UUID id) {
        Asset asset = loadAssetForWrite(id);
        if (!asset.isActive()) return;
        asset.setActive(false);
        asset.setDeactivatedAt(Instant.now());
        assetRepository.save(asset);
        publishAssetEvent(asset, "DEACTIVATED");
    }

    // =========================================================================
    // Hierarchy cascade helpers (package-private for testing)
    // =========================================================================

    void deactivateSitesForCustomer(Customer customer) {
        Specification<Site> activeForCustomer =
                (root, query, cb) -> cb.and(
                        cb.equal(root.get("customerId"), customer.getId()),
                        cb.isTrue(root.get("active")));
        List<Site> activeSites = siteRepository.findAll(activeForCustomer);
        for (Site site : activeSites) {
            site.setActive(false);
            site.setDeactivatedAt(Instant.now());
            deactivateAssetsForSite(site);
            siteRepository.save(site);
            publishSiteEvent(site, "DEACTIVATED");
        }
    }

    void deactivateAssetsForSite(Site site) {
        Specification<Asset> activeForSite =
                (root, query, cb) -> cb.and(
                        cb.equal(root.get("siteId"), site.getId()),
                        cb.isTrue(root.get("active")));
        List<Asset> activeAssets = assetRepository.findAll(activeForSite);
        for (Asset asset : activeAssets) {
            asset.setActive(false);
            asset.setDeactivatedAt(Instant.now());
            assetRepository.save(asset);
            publishAssetEvent(asset, "DEACTIVATED");
        }
    }

    // =========================================================================
    // Load helpers — use unscoped save path (write guard is @PreAuthorize)
    // =========================================================================

    private Customer loadCustomerForWrite(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer", id));
    }

    private Site loadSiteForWrite(UUID id) {
        return siteRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Site", id));
    }

    private Asset loadAssetForWrite(UUID id) {
        return assetRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Asset", id));
    }

    // =========================================================================
    // Mapping helpers
    // =========================================================================

    private CustomerRef toCustomerRef(Customer c) {
        String displayName = c.getLegalName() != null ? c.getLegalName() : c.getName();
        return new CustomerRef(c.getId(), c.getAccountCode(), displayName,
                c.getPrimaryContactName(), c.isActive());
    }

    private SiteRef toSiteRef(Site s) {
        String displayName = s.getDisplayName() != null ? s.getDisplayName() : s.getName();
        return new SiteRef(s.getId(), s.getCustomerId(), s.getSiteCode(), displayName, s.isActive());
    }

    private AssetRef toAssetRef(Asset a) {
        return new AssetRef(a.getId(), a.getSiteId(), a.getAssetTag(), a.getCategory(), a.isActive());
    }

    // =========================================================================
    // Sort helpers
    // =========================================================================

    private static Sort buildSort(SortAllowList allowList, PageQuery pageQuery, String defaultField) {
        if (pageQuery.sort().isEmpty()) {
            return Sort.by(Sort.Direction.ASC, defaultField, "id");
        }
        List<Sort.Order> orders = pageQuery.sort().stream()
                .map(sf -> new Sort.Order(sf.direction(), allowList.resolvePersistentName(sf.field())))
                .collect(Collectors.toList());
        boolean hasId = orders.stream().anyMatch(o -> "id".equals(o.getProperty()));
        if (!hasId) orders.add(Sort.Order.asc("id"));
        return Sort.by(orders);
    }

    // =========================================================================
    // Filter helpers
    // =========================================================================

    private static Specification<Customer> buildCustomerFilter(String q) {
        if (q == null || q.isBlank()) return Specification.where(null);
        String pattern = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("legalName")), pattern),
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("accountCode")), pattern)
        );
    }

    private static Specification<Site> buildSiteFilter(UUID customerId, String q) {
        Specification<Site> byCustomer = (root, query, cb) ->
                cb.equal(root.get("customerId"), customerId);
        if (q == null || q.isBlank()) return byCustomer;
        String pattern = "%" + q.toLowerCase() + "%";
        Specification<Site> search = (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("displayName")), pattern),
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("siteCode")), pattern)
        );
        return byCustomer.and(search);
    }

    private static Specification<Asset> buildAssetFilter(UUID siteId, String q) {
        Specification<Asset> bySite = (root, query, cb) ->
                cb.equal(root.get("siteId"), siteId);
        if (q == null || q.isBlank()) return bySite;
        String pattern = "%" + q.toLowerCase() + "%";
        Specification<Asset> search = (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("assetTag")), pattern),
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("category")), pattern)
        );
        return bySite.and(search);
    }

    // =========================================================================
    // Pagination helpers
    // =========================================================================

    private static <T> PagedResponse<T> toPagedResponse(Page<T> page, HttpServletRequest request) {
        PageMeta meta = PageMeta.of(page.getNumber(), page.getSize(), page.getTotalElements());
        String next = page.getNumber() + 1 < page.getTotalPages()
                ? replacePageParam(request, page.getNumber() + 1, page.getSize()) : null;
        String prev = page.getNumber() > 0
                ? replacePageParam(request, page.getNumber() - 1, page.getSize()) : null;
        return PagedResponse.of(page.getContent(), meta, PageLinks.of(next, prev));
    }

    private static String replacePageParam(HttpServletRequest request, int page, int size) {
        return UriComponentsBuilder.fromRequestUri(request)
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .toUriString();
    }

    // =========================================================================
    // Outbox event helpers
    // =========================================================================

    private void publishCustomerEvent(Customer customer, String changeType) {
        var payload = new CustomerChangedPayload(
                customer.getId(), customer.getAccountCode(), changeType, customer.isActive());
        eventPublisher.publish(DomainEvent.of(
                CustomerChangedPayload.EVENT_TYPE,
                CustomerChangedPayload.AGGREGATE_TYPE,
                customer.getId(),
                Instant.now(),
                MDC.get("traceId"),
                resolveActorId(),
                PiiRedactionUtility.toPayloadMap(payload)));
    }

    private void publishSiteEvent(Site site, String changeType) {
        var payload = new SiteChangedPayload(
                site.getId(), site.getCustomerId(), site.getSiteCode(), changeType, site.isActive());
        eventPublisher.publish(DomainEvent.of(
                SiteChangedPayload.EVENT_TYPE,
                SiteChangedPayload.AGGREGATE_TYPE,
                site.getId(),
                Instant.now(),
                MDC.get("traceId"),
                resolveActorId(),
                PiiRedactionUtility.toPayloadMap(payload)));
    }

    private void publishAssetEvent(Asset asset, String changeType) {
        var payload = new AssetChangedPayload(
                asset.getId(), asset.getSiteId(), asset.getAssetTag(), changeType, asset.isActive());
        eventPublisher.publish(DomainEvent.of(
                AssetChangedPayload.EVENT_TYPE,
                AssetChangedPayload.AGGREGATE_TYPE,
                asset.getId(),
                Instant.now(),
                MDC.get("traceId"),
                resolveActorId(),
                PiiRedactionUtility.toPayloadMap(payload)));
    }

    private UUID resolveActorId() {
        try {
            return scopeResolver.resolve().userId();
        } catch (Exception ex) {
            return null;
        }
    }
}
