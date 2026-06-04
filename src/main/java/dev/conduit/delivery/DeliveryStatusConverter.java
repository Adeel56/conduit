package dev.conduit.delivery;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Maps {@link DeliveryStatus} to the lowercase tokens the V6 {@code status} CHECK uses
 * ({@code pending|in_flight|delivered|failed}). {@code autoApply} so it applies to every
 * {@code DeliveryStatus} mapping (entity field and derived-query parameters) without per-field
 * annotation — which is what lets the native claim query compare {@code status = 'pending'}.
 */
@Converter(autoApply = true)
public class DeliveryStatusConverter implements AttributeConverter<DeliveryStatus, String> {

    @Override
    public String convertToDatabaseColumn(DeliveryStatus status) {
        return status == null ? null : status.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public DeliveryStatus convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : DeliveryStatus.valueOf(dbValue.toUpperCase(Locale.ROOT));
    }
}
