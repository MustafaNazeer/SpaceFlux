package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;

@ExtendWith(OutputCaptureExtension.class)
class OperatorTest {

    static final int COST = 5;
    static final String HASH = new BCryptPasswordEncoder(COST).encode("correct horse");

    @Test
    void aBcryptHashAtTheConfiguredCostMakesTheOperator() {
        var user = Operator.from("operator", "{bcrypt}" + HASH, COST);

        assertThat(user).isPresent();
        assertThat(user.get().getUsername()).isEqualTo("operator");
        assertThat(Operator.encoder(COST).matches("correct horse", user.get().getPassword())).isTrue();
    }

    @Test
    void eachBcryptVersionPrefixIsAccepted() {
        for (String version : new String[] {"$2a$", "$2b$", "$2y$"}) {
            String hash = version + HASH.substring(4);

            assertThat(Operator.from("operator", "{bcrypt}" + hash, COST)).as(version).isPresent();
        }
    }

    @Test
    void aHashMadeOfflineWithPythonsBcryptIsAccepted() {
        // Made with: python3 -c 'import bcrypt; print(bcrypt.hashpw(b"correct horse", bcrypt.gensalt(5)).decode())'
        String python = "$2b$05$vCgR5vTtSiUQLNSt8xHOJOgXBIZqyhv.GNm3spPIIE35/32IXguOK";

        var user = Operator.from("operator", "{bcrypt}" + python, COST);

        assertThat(user).isPresent();
        assertThat(Operator.encoder(COST).matches("correct horse", user.get().getPassword())).isTrue();
        assertThat(Operator.encoder(COST).matches("correct horsf", user.get().getPassword())).isFalse();
    }

    @Test
    void noOperatorAtAllOrHalfOfOneIsWarnedByVariableName(CapturedOutput output) {
        Operator.from("", "", COST);
        assertThat(output.getAll()).contains("No operator is configured");

        Operator.from("operator", "", COST);
        assertThat(output.getAll()).contains("ACK_OPERATOR_PASSWORD_HASH is not set");

        Operator.from("", "{bcrypt}" + HASH, COST);
        assertThat(output.getAll()).contains("ACK_OPERATOR_USERNAME is not set").doesNotContain(HASH);
    }

    @Test
    void aMissingUsernameOrHashMeansNoOperator() {
        assertThat(Operator.from("", "{bcrypt}" + HASH, COST)).isEmpty();
        assertThat(Operator.from("  ", "{bcrypt}" + HASH, COST)).isEmpty();
        assertThat(Operator.from("operator", "", COST)).isEmpty();
    }

    @Test
    void anyOtherFormatCountsAsMissingAndTheWarningNeverShowsTheValue(CapturedOutput output) {
        String[] refused = {"{noop}hunter2hunter2", "{MD5}5f4dcc3b5aa765d61d8327deb882cf99", HASH,
            "{bcrypt}" + new BCryptPasswordEncoder(COST + 1).encode("x"), "{bcrypt}$2a$05$tooShort",
            "{bcrypt}" + HASH + "x", "{BCRYPT}" + HASH, "{bcrypt}$2x$" + HASH.substring(4)};
        int warnings = 0;
        for (String value : refused) {
            assertThat(Operator.from("operator", value, COST)).as(value).isEmpty();
            assertThat(StringUtils.countOccurrencesOf(output.getAll(), "ACK_OPERATOR_PASSWORD_HASH is not"))
                    .as(value).isEqualTo(++warnings);
            assertThat(output.getAll()).doesNotContain(value);
        }
    }

    @Test
    void theEncoderHashesAtTheConfiguredCostSoAnUnknownUserCostsTheSame() {
        // DaoAuthenticationProvider checks an unknown username against a hash it makes with this encoder.
        assertThat(Operator.encoder(COST).encode("anything")).startsWith("$2a$05$");
    }
}
