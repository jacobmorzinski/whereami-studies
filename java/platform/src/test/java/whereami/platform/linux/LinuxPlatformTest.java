package whereami.platform.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import whereami.platform.Location;
import whereami.platform.LocationError;

class LinuxPlatformTest {
    private static Location fix(double accuracy) {
        return new Location(accuracy, 0, accuracy);
    }

    private static long deadlineIn(Duration d) {
        return System.nanoTime() + d.toNanos();
    }

    private static Duration since(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    @Test
    void accessDenied() {
        assertEquals(LocationError.PERMISSION_DENIED,
                LinuxPlatform.fromDbusErrorName("org.freedesktop.DBus.Error.AccessDenied"));
    }

    @Test
    void serviceUnknown() {
        assertEquals(LocationError.LOCATION_UNAVAILABLE,
                LinuxPlatform.fromDbusErrorName("org.freedesktop.DBus.Error.ServiceUnknown"));
    }

    @Test
    void failed() {
        assertEquals(LocationError.LOCATION_UNAVAILABLE,
                LinuxPlatform.fromDbusErrorName("org.freedesktop.DBus.Error.Failed"));
    }

    @Test
    void emptyAndNullNames() {
        assertEquals(LocationError.LOCATION_UNAVAILABLE, LinuxPlatform.fromDbusErrorName(""));
        assertEquals(LocationError.LOCATION_UNAVAILABLE, LinuxPlatform.fromDbusErrorName(null));
    }

    @Test
    void bestFixPrefersFineAfterCoarse() throws Exception {
        BlockingQueue<Location> q = new LinkedBlockingQueue<>();
        q.add(fix(25000));
        q.add(fix(10));
        assertEquals(Optional.of(fix(10)),
                LinuxPlatform.bestFix(q, Duration.ofMillis(50), deadlineIn(Duration.ofSeconds(60))));
    }

    @Test
    void bestFixSingleFixReturnsAfterGrace() throws Exception {
        BlockingQueue<Location> q = new LinkedBlockingQueue<>();
        q.add(fix(5));
        long start = System.nanoTime();
        Duration grace = Duration.ofMillis(50);
        assertEquals(Optional.of(fix(5)), LinuxPlatform.bestFix(q, grace, deadlineIn(Duration.ofSeconds(60))));
        Duration elapsed = since(start);
        assertTrue(elapsed.compareTo(grace) >= 0, "returned early after " + elapsed);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0, "waited " + elapsed);
    }

    @Test
    void bestFixTieKeepsFirst() throws Exception {
        BlockingQueue<Location> q = new LinkedBlockingQueue<>();
        Location first = new Location(1, 1, 10);
        q.add(first);
        q.add(new Location(2, 2, 10));
        assertEquals(Optional.of(first),
                LinuxPlatform.bestFix(q, Duration.ofMillis(50), deadlineIn(Duration.ofSeconds(60))));
    }

    @Test
    void bestFixIgnoresFixAfterWindow() throws Exception {
        BlockingQueue<Location> q = new LinkedBlockingQueue<>();
        q.add(fix(100));
        Thread late = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            q.add(fix(1));
        });
        assertEquals(Optional.of(fix(100)),
                LinuxPlatform.bestFix(q, Duration.ofMillis(20), deadlineIn(Duration.ofSeconds(60))));
        late.join();
    }

    @Test
    void bestFixClampsGraceToDeadline() throws Exception {
        BlockingQueue<Location> q = new LinkedBlockingQueue<>();
        q.add(fix(5));
        long start = System.nanoTime();
        assertEquals(Optional.of(fix(5)),
                LinuxPlatform.bestFix(q, Duration.ofMillis(250), deadlineIn(Duration.ofMillis(30))));
        assertTrue(since(start).compareTo(Duration.ofMillis(150)) < 0, "waited " + since(start));
    }

    @Test
    void bestFixEmptyWithoutAFixByDeadline() throws Exception {
        BlockingQueue<Location> q = new LinkedBlockingQueue<>();
        long start = System.nanoTime();
        assertEquals(Optional.empty(),
                LinuxPlatform.bestFix(q, Duration.ofMillis(250), deadlineIn(Duration.ofMillis(50))));
        Duration elapsed = since(start);
        assertTrue(elapsed.compareTo(Duration.ofMillis(40)) >= 0, "returned early after " + elapsed);
        assertTrue(elapsed.compareTo(Duration.ofMillis(500)) < 0, "waited " + elapsed);
    }
}
