package whereami.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import whereami.platform.Location;

/** Runs with a locale whose decimal separator is a comma. */
class LocaleTest {
    private Locale saved;

    @BeforeEach
    void german() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
    }

    @AfterEach
    void restore() {
        Locale.setDefault(saved);
    }

    @Test
    void humanOutputIsLocaleIndependent() {
        assertTrue(Output.human(new Location(40.7128, -74.0060, 0), null, true)
                .startsWith("Location: 40.7128, -74.0060"));
    }
}
