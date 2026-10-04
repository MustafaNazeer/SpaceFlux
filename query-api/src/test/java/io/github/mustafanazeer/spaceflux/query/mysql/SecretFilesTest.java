package io.github.mustafanazeer.spaceflux.query.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.env.ConfigTreePropertySource.Option;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class SecretFilesTest {

    static final String SECRET = "Q7cVZ0rLwYkS3tHn8pDf2aBx9eMj4uGi6oNq1sRz";

    @TempDir
    Path dir;

    StandardEnvironment environment() throws Exception {
        Files.writeString(dir.resolve("mysql_api_password"), SECRET, StandardCharsets.US_ASCII);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new ConfigTreePropertySource("Config tree '" + dir + "'", dir,
                Option.AUTO_TRIM_TRAILING_NEW_LINE));
        return env;
    }

    @Test
    void thePasswordIsReadFromItsOwnSecretFile() throws Exception {
        assertThat(SecretFiles.password(environment(), "api", "spaceflux_api")).isEqualTo(SECRET);
    }

    @Test
    void aTrailingNewLineInTheFileIsNotPartOfThePassword() throws Exception {
        StandardEnvironment env = environment();
        Files.writeString(dir.resolve("mysql_api_password"), SECRET + "\n", StandardCharsets.US_ASCII);

        assertThat(SecretFiles.password(env, "api", "spaceflux_api")).isEqualTo(SECRET);
    }

    @Test
    void anEnvironmentVariableWithThePasswordIsRefusedEvenWhenTheFileExists() throws Exception {
        StandardEnvironment env = environment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("MYSQL_API_PASSWORD", "from-the-environment")));

        assertThatThrownBy(() -> SecretFiles.password(env, "api", "spaceflux_api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql_api_password is set by systemEnvironment; the api password may come only from "
                        + "its secret file");
    }

    @Test
    void anEnvironmentVariableForTheBoundPropertyIsRefused() throws Exception {
        StandardEnvironment env = environment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("SPACEFLUX_MYSQL_API_PASSWORD", "from-the-environment")));

        assertThatThrownBy(() -> SecretFiles.password(env, "api", "spaceflux_api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spaceflux.mysql.api.password is set by systemEnvironment")
                .hasMessageNotContaining("from-the-environment");
    }

    @Test
    void aPasswordGivenAsAnyOtherPropertyIsRefused() throws Exception {
        StandardEnvironment env = environment();
        env.getPropertySources().addLast(new MapPropertySource("commandLineArgs",
                Map.of("mysql_api_password", "from-a-flag")));

        assertThatThrownBy(() -> SecretFiles.password(env, "api", "spaceflux_api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mysql_api_password is set by commandLineArgs")
                .hasMessageNotContaining("from-a-flag");
    }

    @Test
    void aMissingSecretFileIsRefused() throws Exception {
        assertThatThrownBy(() -> SecretFiles.password(environment(), "consumer", "spaceflux_consumer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no secret file mysql_consumer_password holds the password for spaceflux_consumer");
    }

    @Test
    void anEmptySecretFileIsRefused() throws Exception {
        StandardEnvironment env = environment();
        Files.writeString(dir.resolve("mysql_api_password"), "", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> SecretFiles.password(env, "api", "spaceflux_api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the password for spaceflux_api is empty");
    }
}
