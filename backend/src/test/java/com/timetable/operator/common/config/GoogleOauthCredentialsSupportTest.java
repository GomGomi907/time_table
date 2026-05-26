package com.timetable.operator.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class GoogleOauthCredentialsSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void resolvesEnvironmentCredentialsAfterTrimmingWhitespaceAndWrappingQuotes() {
        GoogleOauthCredentials credentials = GoogleOauthCredentialsSupport.resolve(
                "  \"client-id.apps.googleusercontent.com\"  ",
                "\n'client-secret'\r\n",
                "",
                objectMapper
        );

        assertThat(credentials.clientId()).isEqualTo("client-id.apps.googleusercontent.com");
        assertThat(credentials.clientSecret()).isEqualTo("client-secret");
    }

    @Test
    void resolvesCredentialFileValuesAfterTrimmingWhitespaceAndWrappingQuotes() throws Exception {
        Path credentialsFile = tempDir.resolve("client_secret.json");
        Files.writeString(credentialsFile, """
                {
                  "web": {
                    "client_id": "  \\"client-id.apps.googleusercontent.com\\"  ",
                    "client_secret": "\\n'client-secret'\\r\\n"
                  }
                }
                """);

        GoogleOauthCredentials credentials = GoogleOauthCredentialsSupport.resolve(
                "",
                "",
                credentialsFile.toString(),
                objectMapper
        );

        assertThat(credentials.clientId()).isEqualTo("client-id.apps.googleusercontent.com");
        assertThat(credentials.clientSecret()).isEqualTo("client-secret");
    }
}
