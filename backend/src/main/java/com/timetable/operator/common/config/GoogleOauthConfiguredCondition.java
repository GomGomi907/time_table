package com.timetable.operator.common.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class GoogleOauthConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String clientId = context.getEnvironment().getProperty("app.auth.google-client-id");
        String clientSecret = context.getEnvironment().getProperty("app.auth.google-client-secret");
        String credentialsFile = context.getEnvironment().getProperty("app.auth.google-credentials-file");
        return GoogleOauthCredentialsSupport.hasConfiguredCredentials(clientId, clientSecret, credentialsFile);
    }
}
