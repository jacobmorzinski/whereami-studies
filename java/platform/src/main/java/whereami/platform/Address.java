package whereami.platform;

/** A postal address. Fields the OS didn't supply are empty strings, never null. */
public record Address(String street, String city, String state, String postalCode, String country) {}
