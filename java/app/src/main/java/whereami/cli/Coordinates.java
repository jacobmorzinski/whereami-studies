package whereami.cli;

import java.util.regex.Pattern;
import picocli.CommandLine;
import whereami.platform.Location;

/** Parses "LAT,LON" for --mock and --at. */
final class Coordinates {
    private Coordinates() {}

    /**
     * A plain decimal: optional sign, digits with an optional fraction, optional
     * exponent. Checked before Double.parseDouble, which would also accept
     * "NaN", "Infinity", hex floats ("0x1p3"), type suffixes ("1d"), and
     * surrounding control characters.
     */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    /** Splits at the first comma and trims spaces from each half. */
    static Location parse(String text) {
        int comma = text.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("expected LAT,LON");
        }
        return new Location(number(text.substring(0, comma)), number(text.substring(comma + 1)), 0);
    }

    private static double number(String part) {
        String s = trimSpaces(part);
        if (!DECIMAL.matcher(s).matches()) {
            throw new IllegalArgumentException("'" + part + "' is not a number");
        }
        double value = Double.parseDouble(s); // never locale-dependent
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("'" + part + "' is out of range");
        }
        return value;
    }

    private static String trimSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == ' ') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(start, end);
    }

    /** Lets picocli convert an option's text straight to a {@link Location}. */
    static final class Converter implements CommandLine.ITypeConverter<Location> {
        @Override
        public Location convert(String value) {
            try {
                return parse(value);
            } catch (IllegalArgumentException e) {
                throw new CommandLine.TypeConversionException(e.getMessage());
            }
        }
    }
}
