package whereami.platform;

/** What an address lookup finds: where it is, and the address as the OS normalized it. */
public record GeocodeResult(Location location, Address address) {}
