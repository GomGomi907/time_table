package com.timetable.operator.sync.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.sync.domain.SyncMapping;
import com.timetable.operator.sync.domain.SyncMappingLocalType;
import com.timetable.operator.sync.domain.SyncProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sync-mapping-repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.enabled=false",
        "app.sync.polling.enabled=false"
})
class SyncMappingRepositoryTest {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SyncMappingRepository syncMappingRepository;

    @Test
    void providerExternalIdLookupIsScopedByUser() {
        AppUser firstUser = saveUser("sync-map-a@example.com");
        AppUser secondUser = saveUser("sync-map-b@example.com");

        SyncMapping firstMapping = saveMapping(
                firstUser.getId(),
                SyncMappingLocalType.EVENT,
                UUID.randomUUID(),
                SyncProvider.GOOGLE_CALENDAR,
                "shared-google-event"
        );
        SyncMapping secondMapping = saveMapping(
                secondUser.getId(),
                SyncMappingLocalType.EVENT,
                UUID.randomUUID(),
                SyncProvider.GOOGLE_CALENDAR,
                "shared-google-event"
        );

        assertThat(syncMappingRepository.findByUserIdAndProviderAndExternalId(
                firstUser.getId(),
                SyncProvider.GOOGLE_CALENDAR,
                "shared-google-event"
        ).orElseThrow().getId()).isEqualTo(firstMapping.getId());
        assertThat(syncMappingRepository.findByUserIdAndProviderAndExternalId(
                secondUser.getId(),
                SyncProvider.GOOGLE_CALENDAR,
                "shared-google-event"
        ).orElseThrow().getId()).isEqualTo(secondMapping.getId());
    }

    @Test
    void localLookupIsScopedByUser() {
        AppUser firstUser = saveUser("sync-local-a@example.com");
        AppUser secondUser = saveUser("sync-local-b@example.com");
        UUID sharedLocalId = UUID.randomUUID();

        SyncMapping firstMapping = saveMapping(
                firstUser.getId(),
                SyncMappingLocalType.TASK,
                sharedLocalId,
                SyncProvider.GOOGLE_TASKS,
                "list-a:task-1"
        );
        SyncMapping secondMapping = saveMapping(
                secondUser.getId(),
                SyncMappingLocalType.TASK,
                sharedLocalId,
                SyncProvider.GOOGLE_TASKS,
                "list-b:task-1"
        );

        assertThat(syncMappingRepository.findByUserIdAndLocalTypeAndLocalIdAndProvider(
                firstUser.getId(),
                SyncMappingLocalType.TASK,
                sharedLocalId,
                SyncProvider.GOOGLE_TASKS
        ).orElseThrow().getId()).isEqualTo(firstMapping.getId());
        assertThat(syncMappingRepository.findByUserIdAndLocalTypeAndLocalIdAndProvider(
                secondUser.getId(),
                SyncMappingLocalType.TASK,
                sharedLocalId,
                SyncProvider.GOOGLE_TASKS
        ).orElseThrow().getId()).isEqualTo(secondMapping.getId());
    }

    private AppUser saveUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(email);
        user.setProvider("google");
        user.setDemoUser(false);
        user.setTimezone("Asia/Seoul");
        user.setAutoRescheduleEnabled(false);
        user.setFocusAutoEnterEnabled(false);
        return appUserRepository.save(user);
    }

    private SyncMapping saveMapping(
            UUID userId,
            SyncMappingLocalType localType,
            UUID localId,
            SyncProvider provider,
            String externalId
    ) {
        SyncMapping mapping = new SyncMapping();
        mapping.setUserId(userId);
        mapping.setLocalType(localType);
        mapping.setLocalId(localId);
        mapping.setProvider(provider);
        mapping.setExternalId(externalId);
        return syncMappingRepository.saveAndFlush(mapping);
    }
}

