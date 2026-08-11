package com.fieldservice.platform.util;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Hibernate identifier generator that produces RFC 9562 UUIDv7 values.
 *
 * <p>Used via the {@link GeneratedUuidV7} annotation on entity {@code @Id} fields:
 * <pre>
 *   {@literal @}Id
 *   {@literal @}GeneratedUuidV7
 *   {@literal @}Column(name = "id", updatable = false)
 *   private UUID id;
 * </pre>
 *
 * <p>Because this is a {@link BeforeExecutionGenerator}, Hibernate calls
 * {@link #generate} before issuing the INSERT, so the generated UUID is
 * available in the returned entity for immediate use.
 *
 * @see UuidV7
 */
public class UuidV7Generator implements BeforeExecutionGenerator {

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }

    @Override
    public Object generate(
            SharedSessionContractImplementor session,
            Object owner,
            Object currentValue,
            EventType eventType) {

        if (currentValue instanceof UUID existing) {
            // If the entity already has an ID (e.g., set in a test fixture), keep it.
            return existing;
        }
        return UuidV7.generate();
    }
}
