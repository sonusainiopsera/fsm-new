package com.fieldservice.platform.security;

/**
 * Domain modules implement this interface to register their entity-specific
 * {@link org.springframework.data.jpa.domain.Specification} builders with the
 * {@link AccessScopePredicateFactory}.
 *
 * <p>Spring collects all implementations via {@code @Autowired List<AccessScopeSpecificationContributor>}
 * in the factory, so each domain module participates without hard-coding entity names
 * in the platform module.
 */
public interface AccessScopeSpecificationContributor {

    /**
     * Register entity-type-specific scope specifications.
     *
     * @param factory the factory to register into; never null
     */
    void contribute(AccessScopePredicateFactory factory);
}
