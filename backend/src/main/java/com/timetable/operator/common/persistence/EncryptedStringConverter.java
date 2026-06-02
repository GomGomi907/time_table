package com.timetable.operator.common.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank() || attribute.startsWith(PREFIX)) {
            return attribute;
        }
        String key = encryptionKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY is required before storing sensitive tokens.");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        } catch (Exception exception) {
            throw new IllegalStateException("민감 정보를 암호화하지 못했습니다.", exception);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || !dbData.startsWith(PREFIX)) {
            return dbData;
        }
        String key = encryptionKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("암호화된 민감 정보를 읽으려면 APP_ENCRYPTION_KEY가 필요합니다.");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(dbData.substring(PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("암호화된 민감 정보를 복호화하지 못했습니다.", exception);
        }
    }

    private static SecretKeySpec secretKey(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private static String encryptionKey() {
        String configured = EncryptionKeyProvider.configuredKey();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String appProperty = System.getProperty("app.encryption.key");
        if (appProperty != null && !appProperty.isBlank()) {
            return appProperty;
        }
        String property = System.getProperty("APP_ENCRYPTION_KEY");
        if (property != null && !property.isBlank()) {
            return property;
        }
        return System.getenv("APP_ENCRYPTION_KEY");
    }
}
