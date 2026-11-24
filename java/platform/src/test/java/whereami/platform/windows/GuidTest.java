package whereami.platform.windows;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import org.junit.jupiter.api.Test;

class GuidTest {
    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    @Test
    void layout() {
        try (Arena arena = Arena.ofConfined()) {
            assertArrayEquals(
                    bytes(0x36, 0, 0, 0, 0, 0, 0, 0, 0xC0, 0, 0, 0, 0, 0, 0, 0x46),
                    Guid.allocate(arena, "00000036-0000-0000-C000-000000000046").toArray(JAVA_BYTE));
        }
    }

    @Test
    void layoutMixed() {
        try (Arena arena = Arena.ofConfined()) {
            assertArrayEquals(
                    bytes(0x62, 0xBF, 0xC3, 0xA9, 0x24, 0x45, 0x89, 0x49,
                            0x8A, 0xA9, 0xDE, 0x01, 0x9D, 0x2E, 0x55, 0x1F),
                    Guid.allocate(arena, "A9C3BF62-4524-4989-8AA9-DE019D2E551F").toArray(JAVA_BYTE));
        }
    }
}
