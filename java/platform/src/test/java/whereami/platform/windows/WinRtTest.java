package whereami.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Real calls into combase.dll. Needs Windows, but not location permission. */
@EnabledOnOs(OS.WINDOWS)
class WinRtTest {
    @Test
    void getsGeolocatorActivationFactory() {
        assertTrue(WinRt.roInitialize(WinRt.RO_INIT_MULTITHREADED) >= 0);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment className = WinRt.windowsCreateString(WindowsPlatform.GEOLOCATOR_CLASS, arena);
            try {
                MemorySegment out = arena.allocate(ADDRESS);
                int hr = WinRt.roGetActivationFactory(
                        className, Guid.allocate(arena, WindowsPlatform.IID_IGEOLOCATOR_STATICS), out);
                assertEquals(0, hr);
                MemorySegment statics = out.get(ADDRESS, 0);
                assertNotEquals(MemorySegment.NULL, statics);
                Com.release(statics);
            } finally {
                WinRt.windowsDeleteString(className);
            }
        } finally {
            WinRt.roUninitialize();
        }
    }
}
