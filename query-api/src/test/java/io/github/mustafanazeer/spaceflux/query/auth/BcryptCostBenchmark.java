package io.github.mustafanazeer.spaceflux.query.auth;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Measures how long one password check takes at each bcrypt cost, to choose the cost nearest one second (ADR 0009,
 * amendment), for docs/perf/bcrypt-cost.md. It is not part of the default build; run it from the repository root with
 *
 * <pre>
 * ./mvnw -B -q -pl query-api -am test -Dtest=BcryptCostBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dbcrypt.cost=true -Dbcrypt.cost.out=$PWD/docs/perf/data/bcrypt-cost-NAME.txt
 * </pre>
 *
 * Each figure is wall clock time from {@link System#nanoTime()} on one thread for the check a login runs,
 * {@link PasswordEncoder#matches} with the service's own encoder, after warm up calls.
 */
@EnabledIfSystemProperty(named = "bcrypt.cost", matches = "true")
class BcryptCostBenchmark {

    private static final int FIRST_COST = 10;
    private static final int LAST_COST = 14;
    private static final int WARMUP = 2;
    private static final int REPEATS = 7;

    private static volatile boolean sink;

    @Test
    void measuresOnePasswordCheckAtEachCost() throws Exception {
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        var os = ManagementFactory.getOperatingSystemMXBean();
        out.printf(Locale.ROOT, "bcrypt cost of one password check (Operator.encoder(cost).matches), %s%n",
                Instant.now());
        out.printf(Locale.ROOT, "java %s (%s), %d available processors, load average %.2f at start%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                Runtime.getRuntime().availableProcessors(), os.getSystemLoadAverage());
        out.printf(Locale.ROOT, "%d warm up and %d measured checks per cost, times in ms%n", WARMUP, REPEATS);
        out.println("cost\tmedian\tmin\tmax");
        for (int cost = FIRST_COST; cost <= LAST_COST; cost++) {
            PasswordEncoder encoder = Operator.encoder(cost);
            String hash = encoder.encode("a password of ordinary length");
            for (int i = 0; i < WARMUP; i++) {
                sink = encoder.matches("not the password", hash);
            }
            double[] ms = new double[REPEATS];
            for (int i = 0; i < REPEATS; i++) {
                long start = System.nanoTime();
                sink = encoder.matches("not the password", hash);
                ms[i] = (System.nanoTime() - start) / 1e6;
            }
            Arrays.sort(ms);
            out.printf(Locale.ROOT, "%d\t%.1f\t%.1f\t%.1f%n", cost, ms[REPEATS / 2], ms[0], ms[REPEATS - 1]);
        }
        out.printf(Locale.ROOT, "load average %.2f at end%n", os.getSystemLoadAverage());
        out.flush();
        System.out.print(text);
        String file = System.getProperty("bcrypt.cost.out");
        if (file != null) {
            Files.writeString(Path.of(file), text.toString(), StandardCharsets.UTF_8);
        }
    }
}
