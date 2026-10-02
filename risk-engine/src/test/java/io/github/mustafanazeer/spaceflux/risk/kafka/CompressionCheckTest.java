package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** A codec this JVM cannot decode stops the risk engine at start instead of failing every poll. */
class CompressionCheckTest {

    @Test
    void everyCodecAProducerMayUseDecodesHere() {
        assertThatCode(() -> CompressionCheck.check(CompressionCheck.CODECS)).doesNotThrowAnyException();
    }

    @Test
    void aCodecThatCannotLoadItsNativeLibraryStopsTheStartAndNamesItsSetting() {
        List<CompressionCheck.Codec> broken = List.of(new CompressionCheck.Codec("snappy", "org.xerial.snappy.tempdir",
                () -> {
                    throw new UnsatisfiedLinkError("failed to map segment from shared object");
                }));

        assertThatThrownBy(() -> CompressionCheck.check(broken)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snappy").hasMessageContaining("org.xerial.snappy.tempdir")
                .hasMessageContaining("failed to map segment");
    }
}
