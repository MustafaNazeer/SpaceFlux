package io.github.mustafanazeer.spaceflux.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettingsTest {

    @TempDir
    Path dir;

    private Map<String, String> environment() throws Exception {
        Path secret = dir.resolve("mysql_migrate_password");
        Files.writeString(secret, "a".repeat(40) + "\n");
        Map<String, String> env = new HashMap<>();
        env.put("MIGRATE_PASSWORD_FILE", secret.toString());
        env.put("MIGRATE_USER", "spaceflux_migrate");
        env.put("CONSUMER_USER", "spaceflux_consumer");
        env.put("API_USER", "spaceflux_api");
        return env;
    }

    @Test
    void readsThePasswordFromItsFileAndDefaultsToTheComposeService() throws Exception {
        Migrate.Settings settings = Migrate.Settings.fromEnvironment(environment());

        assertThat(settings.password()).isEqualTo("a".repeat(40));
        assertThat(settings.jdbcUrl()).startsWith("jdbc:mysql://mysql:3306/spaceflux?sslMode=REQUIRED&");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api'@'%", "api\"", "Api", "1api", "", "a_name_that_is_thirty_three_chars", "api user",
            "api;drop"})
    void aUserNameOutsideThePatternIsRefusedBeforeFlywayStarts(String name) throws Exception {
        Map<String, String> env = environment();
        env.put("API_USER", name);

        assertThatThrownBy(() -> Migrate.Settings.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API_USER");
    }

    @Test
    void aMissingConsumerUserIsRefused() throws Exception {
        Map<String, String> env = environment();
        env.remove("CONSUMER_USER");

        assertThatThrownBy(() -> Migrate.Settings.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONSUMER_USER");
    }

    @Test
    void thirtyTwoCharactersIsTheLongestName() throws Exception {
        Map<String, String> env = environment();
        env.put("API_USER", "a".repeat(32));

        assertThat(Migrate.Settings.fromEnvironment(env).apiUser()).hasSize(32);
    }

    @Test
    void onlyTheVerifyingSslModesAreAcceptedBesideRequired() throws Exception {
        Map<String, String> env = environment();
        env.put("MIGRATE_SSL_MODE", "DISABLED");

        assertThatThrownBy(() -> Migrate.Settings.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIGRATE_SSL_MODE");

        env.put("MIGRATE_SSL_MODE", "VERIFY_IDENTITY");
        assertThat(Migrate.Settings.fromEnvironment(env).jdbcUrl()).contains("sslMode=VERIFY_IDENTITY&");
    }

    @Test
    void aHostCannotCarryUrlParameters() throws Exception {
        Map<String, String> env = environment();
        env.put("MIGRATE_HOST", "mysql/spaceflux?allowLoadLocalInfile=true#");

        assertThatThrownBy(() -> Migrate.Settings.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIGRATE_HOST");
    }

    @Test
    void anEmptyPasswordFileIsRefused() throws Exception {
        Map<String, String> env = environment();
        Files.writeString(Path.of(env.get("MIGRATE_PASSWORD_FILE")), "\n");

        assertThatThrownBy(() -> Migrate.Settings.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void theUrlTurnsOffKeyRetrievalAndLocalFilesAndPinsTheSessionToUtc() throws Exception {
        String url = Migrate.Settings.fromEnvironment(environment()).jdbcUrl();

        assertThat(url).contains("allowPublicKeyRetrieval=false", "allowLoadLocalInfile=false",
                "allowUrlInLocalInfile=false", "connectionTimeZone=%2B00:00", "forceConnectionTimeZoneToSession=true");
    }

    @Test
    void thePasswordNeverAppearsInTheSettingsText() throws Exception {
        Migrate.Settings settings = Migrate.Settings.fromEnvironment(environment());

        assertThat(settings.toString()).doesNotContain(settings.password());
    }
}
