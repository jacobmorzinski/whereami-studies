package whereami.platform.linux;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.MethodNoReply;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/** The slice of GeoClue2's D-Bus API we use, as dbus-java interfaces. */
final class GeoClue {
    private GeoClue() {}

    static final String SERVICE = "org.freedesktop.GeoClue2";
    static final String MANAGER_PATH = "/org/freedesktop/GeoClue2/Manager";
    static final String CLIENT_INTERFACE = "org.freedesktop.GeoClue2.Client";
    static final String LOCATION_INTERFACE = "org.freedesktop.GeoClue2.Location";

    @DBusInterfaceName("org.freedesktop.GeoClue2.Manager")
    interface Manager extends DBusInterface {
        DBusPath CreateClient();
    }

    @DBusInterfaceName(CLIENT_INTERFACE)
    interface Client extends DBusInterface {
        void Start();

        /** Fire and forget: don't wait for GeoClue's reply. */
        @MethodNoReply
        void Stop();

        /** {@code old} and {@code new} are paths of {@code Location} objects. */
        class LocationUpdated extends DBusSignal {
            private final DBusPath oldLocation;
            private final DBusPath newLocation;

            public LocationUpdated(String path, DBusPath oldLocation, DBusPath newLocation) throws DBusException {
                super(path, oldLocation, newLocation);
                this.oldLocation = oldLocation;
                this.newLocation = newLocation;
            }

            DBusPath oldLocation() {
                return oldLocation;
            }

            DBusPath newLocation() {
                return newLocation;
            }
        }
    }
}
