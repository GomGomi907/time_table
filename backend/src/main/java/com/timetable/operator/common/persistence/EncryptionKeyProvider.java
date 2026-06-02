package com.timetable.operator.common.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EncryptionKeyProvider {

    private static volatile String configuredKey;

    public EncryptionKeyProvider(@Value("${app.encryption.key:}") String key) {
        configuredKey = key;
    }

    static String configuredKey() {
        return configuredKey;
    }
}
