package whereami.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import whereami.platform.Location;

class CoordinatesTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
        "40.7128,-74.0060|40.7128|-74.006",
        " 51.5 , -0.1276|51.5|-0.1276",
        "-33.8688,151.2093|-33.8688|151.2093",
        "1e1,+2|10|2",
    })
    void parses(String text, double lat, double lon) {
        assertEquals(new Location(lat, lon, 0), Coordinates.parse(text));
    }

    /** Double.parseDouble would accept most of these. */
    @ParameterizedTest
    @ValueSource(strings = {
        "40.7128", "abc,def", "1,2,3", "nan,0", "NaN,0", "0,inf", "Infinity,0",
        "0x1p3,0", "1d,2", "1f,2", "1e999,0", "\t1,2", ",", "",
    })
    void rejectsNonDecimal(String text) {
        assertThrows(IllegalArgumentException.class, () -> Coordinates.parse(text));
    }
}
