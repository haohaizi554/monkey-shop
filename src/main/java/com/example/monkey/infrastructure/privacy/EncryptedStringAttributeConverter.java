package com.example.monkey.infrastructure.privacy;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

@Component
@Converter(autoApply = false)
public class EncryptedStringAttributeConverter implements AttributeConverter<String, String> {

    private final PiiCryptoService piiCryptoService;

    public EncryptedStringAttributeConverter(PiiCryptoService piiCryptoService) {
        this.piiCryptoService = piiCryptoService;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return piiCryptoService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return piiCryptoService.decrypt(dbData);
    }
}
