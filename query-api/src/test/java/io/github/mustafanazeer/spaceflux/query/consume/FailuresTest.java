package io.github.mustafanazeer.spaceflux.query.consume;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;

class FailuresTest {

    @Test
    void aDatabaseErrorIsDescribedByItsCodeStateAndMessage() {
        SQLException sql = new SQLException("INSERT command denied to user 'spaceflux_consumer'", "42000", 1142);

        assertThat(Failures.describe(new BadSqlGrammarException("insert", "INSERT ...", sql)))
                .isEqualTo("SQLException 1142 42000: INSERT command denied to user 'spaceflux_consumer'");
    }

    @Test
    void lineBreaksInAMessageCannotStartAForgedLogLine() {
        SQLException sql = new SQLException("Duplicate entry 'a\r\n2026-10-04 WARN forged' for key 'x'", "23000",
                1062);

        assertThat(Failures.describe(new RuntimeException(sql)))
                .doesNotContain("\r").doesNotContain("\n")
                .isEqualTo("SQLException 1062 23000: Duplicate entry 'a\\r\\n2026-10-04 WARN forged' for key 'x'");
    }

    @Test
    void everyOtherControlCharacterAndTheUnicodeLineSeparatorsAreEscapedToo() {
        SQLException sql = new SQLException("a\u001b[31mred\u0000\u007f\u2028\u2029\tb", "23000", 1062);

        assertThat(Failures.describe(sql))
                .isEqualTo("SQLException 1062 23000: a\\u001b[31mred\\u0000\\u007f\\u2028\\u2029\tb");
    }

    @Test
    void c1ControlsAndFormatCharactersSuchAsBidiMarksAreEscaped() {
        assertThat(Failures.oneLine("a\u0085b\u009bc\u202ed\u2066e\u200bf\ufeffg"))
                .isEqualTo("a\\u0085b\\u009bc\\u202ed\\u2066e\\u200bf\\ufeffg");
    }

    /** A tag character takes two chars, each of which alone reads as a surrogate rather than a format character. */
    @Test
    void formatCharactersOutsideTheBasicPlaneAndLoneSurrogatesAreEscaped() {
        assertThat(Failures.oneLine("a\udb40\udc01b\udb40\udc41c\ud800d"))
                .isEqualTo("a\\udb40\\udc01b\\udb40\\udc41c\\ud800d");
    }

    /** Otherwise a literal backslash and n in the data would read the same as an escaped line break. */
    @Test
    void aBackslashIsEscapedSoAnEscapeIsNeverAmbiguous() {
        assertThat(Failures.oneLine("a\\nb\nc")).isEqualTo("a\\\\nb\\nc");
    }

    @Test
    void ordinaryTextOutsideAsciiIsKept() {
        assertThat(Failures.oneLine("caf\u00e9 \u00b0C \u65e5\u672c")).isEqualTo("caf\u00e9 \u00b0C \u65e5\u672c");
    }

    @Test
    void anyOtherErrorIsDescribedByItsInnermostCause() {
        Exception e = new IllegalStateException("writing a dead letter failed",
                new java.util.concurrent.TimeoutException("no ack\nafter 40 s"));

        assertThat(Failures.describe(e)).isEqualTo("TimeoutException: no ack\\nafter 40 s");
    }
}
