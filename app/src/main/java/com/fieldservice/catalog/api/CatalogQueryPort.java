package com.fieldservice.catalog.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Public read-only interface for catalog lookups used by the workorder and inventory modules.
 *
 * <p>This is the <em>only</em> surface of the catalog module visible to other bounded contexts.
 * Callers must not import catalog entities, repositories, or service implementations.
 *
 * <p>All methods enforce the caller's row scope via the mandatory AccessScope predicate.
 * An identifier that is out-of-scope returns {@link Optional#empty()} rather than throwing,
 * so callers cannot distinguish between absent and forbidden records (AC-6 non-disclosure).
 */
public interface CatalogQueryPort {

    /**
     * Looks up a customer by its UUID within the caller's access scope.
     *
     * @param customerId the customer UUID to look up
     * @return the customer ref, or empty if not found or out-of-scope
     */
    Optional<CustomerRef> findCustomerById(UUID customerId);

    /**
     * Looks up a site by its UUID within the caller's access scope.
     *
     * @param siteId the site UUID to look up
     * @return the site ref, or empty if not found or out-of-scope
     */
    Optional<SiteRef> findSiteById(UUID siteId);

    /**
     * Looks up an asset by its UUID within the caller's access scope.
     *
     * @param assetId the asset UUID to look up
     * @return the asset ref, or empty if not found or out-of-scope
     */
    Optional<AssetRef> findAssetById(UUID assetId);

    /**
     * Returns true if the given customer exists and is active within the caller's scope.
     *
     * <p>Used by hierarchy guards in the workorder module.
     */
    boolean isCustomerActive(UUID customerId);

    /**
     * Returns true if the given site exists and is active within the caller's scope.
     *
     * <p>Used by hierarchy guards in the workorder module.
     */
    boolean isSiteActive(UUID siteId);
}
