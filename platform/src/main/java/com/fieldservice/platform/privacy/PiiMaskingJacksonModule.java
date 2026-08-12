package com.fieldservice.platform.privacy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Jackson module that masks {@code CONFIDENTIAL} and {@code RESTRICTED} String fields
 * on serialisation, using the classification tier resolved via {@link FieldTierProvider}.
 *
 * <p>Registered as a Spring bean so Spring Boot's Jackson auto-configuration picks it up
 * automatically.  Non-String fields and fields with {@code PUBLIC}/{@code INTERNAL} tiers
 * are passed through unchanged.
 */
@Component
public class PiiMaskingJacksonModule extends SimpleModule {

    private static final Logger log = LoggerFactory.getLogger(PiiMaskingJacksonModule.class);

    public PiiMaskingJacksonModule(FieldTierProvider tierProvider) {
        super("pii-masking-module");
        setSerializerModifier(new MaskingSerializerModifier(tierProvider));
    }

    // ---- Inner modifier -------------------------------------------------------

    static final class MaskingSerializerModifier extends BeanSerializerModifier {

        private final FieldTierProvider tierProvider;

        MaskingSerializerModifier(FieldTierProvider tierProvider) {
            this.tierProvider = tierProvider;
        }

        @Override
        public List<BeanPropertyWriter> changeProperties(
                SerializationConfig config,
                BeanDescription beanDesc,
                List<BeanPropertyWriter> beanProperties) {

            Class<?> beanClass = beanDesc.getBeanClass();
            List<BeanPropertyWriter> result = new ArrayList<>(beanProperties.size());

            for (BeanPropertyWriter writer : beanProperties) {
                // Only mask String fields — numeric/object fields handled elsewhere
                if (writer.getType().getRawClass() != String.class) {
                    result.add(writer);
                    continue;
                }

                Optional<MaskingTier> tierOpt = Optional.empty();
                try {
                    tierOpt = tierProvider.getTier(beanClass, writer.getName());
                } catch (Exception e) {
                    log.error("masking_tier_lookup_error bean={} field={}", beanClass.getSimpleName(), writer.getName());
                }

                if (tierOpt.isEmpty()) {
                    result.add(writer);
                    continue;
                }

                MaskingTier tier = tierOpt.get();
                if (tier == MaskingTier.RESTRICTED || tier == MaskingTier.CONFIDENTIAL) {
                    result.add(writer.withSerializer(new MaskingStringSerializer(tier)));
                } else {
                    result.add(writer);
                }
            }
            return result;
        }
    }

    // ---- Masking serializer ---------------------------------------------------

    @SuppressWarnings("serial")
    static final class MaskingStringSerializer extends StdSerializer<Object> {

        private final MaskingTier tier;

        MaskingStringSerializer(MaskingTier tier) {
            super(Object.class);
            this.tier = tier;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            String raw = (value == null) ? null : value.toString();
            String masked;
            try {
                masked = MaskingStrategies.forTier(tier).mask(raw);
            } catch (Exception e) {
                masked = MaskingStrategy.REDACTED;
            }
            gen.writeString(masked);
        }
    }
}
