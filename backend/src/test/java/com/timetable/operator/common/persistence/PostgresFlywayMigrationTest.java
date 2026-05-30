package com.timetable.operator.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresFlywayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("timetable_migration")
            .withUsername("timetable")
            .withPassword("timetable");

    @Test
    void committedFlywayMigrationsApplyAndValidateOnRealPostgres() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();

        var migration = flyway.migrate();
        assertThat(migration.success).isTrue();
        assertThat(migration.migrationsExecuted).isGreaterThan(0);

        flyway.validate();

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
                var statement = connection.prepareStatement("select count(*) from flyway_schema_history")) {
            var resultSet = statement.executeQuery();
            resultSet.next();
            assertThat(resultSet.getInt(1)).isGreaterThan(0);
        }

        var secondRun = flyway.migrate();
        assertThat(secondRun.success).isTrue();
        assertThat(secondRun.migrationsExecuted).isZero();
    }
}
