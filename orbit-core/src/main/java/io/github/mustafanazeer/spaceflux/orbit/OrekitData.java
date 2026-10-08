package io.github.mustafanazeer.spaceflux.orbit;

import org.orekit.data.ClasspathCrawler;
import org.orekit.data.DataContext;
import org.orekit.time.TimeScale;

public final class OrekitData {

    private static final String LEAP_SECONDS = "orekit-data/tai-utc.dat";

    private static boolean loaded;

    private OrekitData() {
    }

    public static synchronized void load() {
        if (!loaded) {
            DataContext.getDefault().getDataProvidersManager()
                    .addProvider(new ClasspathCrawler(OrekitData.class.getClassLoader(), LEAP_SECONDS));
            loaded = true;
        }
    }

    public static TimeScale utc() {
        load();
        return DataContext.getDefault().getTimeScales().getUTC();
    }
}
