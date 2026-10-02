package io.github.mustafanazeer.spaceflux.risk.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.utils.Utils;
import org.springframework.stereotype.Component;

/**
 * Decodes one record in every codec a producer may use before any listener starts. snappy and zstd unpack a native
 * library into a temporary directory and load it from there, which fails on a noexec mount; without this check the
 * failure shows up only as a consumer error on every poll.
 */
@Component
public final class CompressionCheck {

    private static final byte[] PROBE = "spaceflux".getBytes(StandardCharsets.UTF_8);

    /** {@code setting} is the system property that moves the codec's native library, or empty when it has none. */
    record Codec(String name, String setting, Runnable roundTrip) {
    }

    static final List<Codec> CODECS = List.of(
            new Codec("gzip", "", () -> roundTrip(Compression.gzip().build())),
            new Codec("snappy", "org.xerial.snappy.tempdir", () -> roundTrip(Compression.snappy().build())),
            new Codec("lz4", "", () -> roundTrip(Compression.lz4().build())),
            new Codec("zstd", "ZstdTempFolder", () -> roundTrip(Compression.zstd().build())));

    CompressionCheck() {
        check(CODECS);
    }

    static void check(List<Codec> codecs) {
        for (Codec c : codecs) {
            try {
                c.roundTrip().run();
            } catch (Exception | LinkageError t) {
                String hint = c.setting().isEmpty() ? ""
                        : "; its native library is unpacked into the directory named by -D" + c.setting()
                                + " (or java.io.tmpdir), which must allow exec";
                throw new IllegalStateException("cannot decode " + c.name() + " compressed records" + hint + ": " + t,
                        t);
            }
        }
    }

    private static void roundTrip(Compression compression) {
        MemoryRecords records = MemoryRecords.withRecords(compression, new SimpleRecord(null, PROBE));
        for (Record r : records.records()) {
            if (!Arrays.equals(Utils.toArray(r.value()), PROBE)) {
                throw new IllegalStateException("decoded value differs from the one written");
            }
        }
    }
}
