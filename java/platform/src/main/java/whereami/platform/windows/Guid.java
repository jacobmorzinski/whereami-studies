package whereami.platform.windows;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.nio.ByteOrder;

/**
 * A Windows GUID in native memory. The struct is
 * {@code { u32 data1; u16 data2; u16 data3; u8 data4[8]; }}, so the first
 * three groups of the text form are stored little-endian and the last two
 * byte by byte. (Zig's {@code windows.zig} writes the same struct by hand.)
 */
final class Guid {
    private Guid() {}

    static MemorySegment allocate(SegmentAllocator allocator, String text) {
        String hex = text.replace("-", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException("not a GUID: " + text);
        }
        MemorySegment guid = allocator.allocate(16);
        guid.set(JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, (int) Long.parseLong(hex.substring(0, 8), 16));
        guid.set(JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        guid.set(JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) {
            int at = 16 + 2 * i;
            guid.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(hex.substring(at, at + 2), 16));
        }
        return guid;
    }
}
