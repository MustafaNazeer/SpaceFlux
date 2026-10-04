package io.github.mustafanazeer.spaceflux.query.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MysqlPropertiesTest {

    static final MysqlProperties.Account CONSUMER = new MysqlProperties.Account("spaceflux_consumer");
    static final MysqlProperties.Account API = new MysqlProperties.Account("spaceflux_api");

    static MysqlProperties properties(String host, int port, String database, String sslMode) {
        return new MysqlProperties(host, port, database, sslMode, CONSUMER, API);
    }

    @Test
    void theUrlRequiresTlsKeepsKeyRetrievalAndLocalFilesOffAndRunsTheSessionInUtc() {
        String url = properties("mysql", 3306, "spaceflux", "REQUIRED").jdbcUrl();

        assertThat(url).isEqualTo("jdbc:mysql://mysql:3306/spaceflux?sslMode=REQUIRED"
                + "&allowPublicKeyRetrieval=false&allowLoadLocalInfile=false&allowUrlInLocalInfile=false"
                + "&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"})
    void everyTlsModeThatRefusesPlaintextIsAccepted(String mode) {
        assertThat(properties("mysql", 3306, "spaceflux", mode).jdbcUrl()).contains("sslMode=" + mode + "&");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "PREFERRED", "required", ""})
    void aTlsModeThatAllowsPlaintextIsRefused(String mode) {
        assertThatThrownBy(() -> properties("mysql", 3306, "spaceflux", mode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ssl-mode");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mysql?allowLoadLocalInfile=true", "mysql/x", "my sql", ""})
    void aHostThatCouldCarryUrlSyntaxIsRefused(String host) {
        assertThatThrownBy(() -> properties(host, 3306, "spaceflux", "REQUIRED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65536})
    void aPortOutOfRangeIsRefused(int port) {
        assertThatThrownBy(() -> properties("mysql", port, "spaceflux", "REQUIRED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void aDatabaseNameThatCouldCarryUrlSyntaxIsRefused() {
        assertThatThrownBy(() -> properties("mysql", 3306, "spaceflux?allowLoadLocalInfile=true", "REQUIRED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Spaceflux_api", "spaceflux'api", "1api", "a23456789012345678901234567890123"})
    void aUserNameOutsideTheAgreedPatternIsRefused(String name) {
        assertThatThrownBy(() -> new MysqlProperties.Account(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }
}
