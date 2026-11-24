package whereami.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import whereami.platform.Address;
import whereami.platform.Location;

class OutputTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void humanWithAccuracy() {
        assertEquals("Location: 40.7128, -74.0060\nAccuracy: 0m\n",
                Output.human(new Location(40.7128, -74.0060, 0), null, true));
    }

    @Test
    void humanWithoutAccuracySkipsEmptyFields() {
        assertEquals("Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n",
                Output.human(new Location(38.8977, -77.0366, 5),
                        new Address("1600 Pennsylvania Ave NW", "Washington", "", "20500", "US"), false));
    }

    @Test
    void jsonNullAddressAndKeyOrder() {
        JsonNode root = MAPPER.readTree(Output.json(new Location(40.7128, -74.006, 0), null));
        assertEquals(40.7128, root.get("latitude").asDouble());
        assertTrue(root.get("address").isNull());
        List<String> names = new ArrayList<>(root.propertyNames());
        assertEquals(List.of("latitude", "longitude", "accuracy", "address"), names);
    }

    @Test
    void jsonEscapesAddress() {
        String json = Output.json(new Location(1, 2, 3), new Address("5 \"Q\" St\\", "Montréal", "", "H2X", "CA"));
        JsonNode address = MAPPER.readTree(json).get("address");
        assertEquals("5 \"Q\" St\\", address.get("street").asString());
        assertEquals("H2X", address.get("postal_code").asString());
        assertTrue(json.contains("Montréal"));
    }

    @Test
    void jsonErrorShape() {
        assertEquals("{\"error\":\"timeout\"}\n", Output.jsonError("timeout"));
    }
}
