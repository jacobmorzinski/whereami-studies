package whereami.cli;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import whereami.platform.Address;
import whereami.platform.Location;

/** Formats results for stdout. Pure: strings in, strings out. */
final class Output {
    private Output() {}

    // postalCode → "postal_code". Records serialize their components in declaration order.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private record Body(double latitude, double longitude, double accuracy, Address address) {}

    private record Error(String error) {}

    /** Locale.ROOT: always "40.7128", never "40,7128", whatever the machine's language. */
    static String human(Location loc, Address address, boolean showAccuracy) {
        var out = new StringBuilder(String.format(Locale.ROOT, "Location: %.4f, %.4f\n", loc.latitude(), loc.longitude()));
        if (showAccuracy) {
            out.append(String.format(Locale.ROOT, "Accuracy: %.0fm\n", loc.accuracy()));
        }
        if (address != null) {
            String line = Stream.of(address.street(), address.city(), address.state(), address.postalCode(), address.country())
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(", "));
            if (!line.isEmpty()) {
                out.append("Address: ").append(line).append('\n');
            }
        }
        return out.toString();
    }

    /** A null address is written as "address":null. */
    static String json(Location loc, Address address) {
        return MAPPER.writeValueAsString(new Body(loc.latitude(), loc.longitude(), loc.accuracy(), address)) + "\n";
    }

    static String jsonError(String key) {
        return MAPPER.writeValueAsString(new Error(key)) + "\n";
    }
}
