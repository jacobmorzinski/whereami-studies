#if !WINDOWS
using Tmds.DBus;

// GeoClue2's D-Bus interfaces, declared as annotated C# interfaces. Tmds.DBus
// generates the proxy classes from these at run time (hence public: the
// generated assembly has to see them), so there is no hand-written marshalling.
namespace Whereami.GeoClue;

public static class Names
{
    public const string Service = "org.freedesktop.GeoClue2";
    public static readonly ObjectPath ManagerPath = new("/org/freedesktop/GeoClue2/Manager");
}

[DBusInterface("org.freedesktop.GeoClue2.Manager")]
public interface IManager : IDBusObject
{
    Task<ObjectPath> CreateClientAsync();
}

[DBusInterface("org.freedesktop.GeoClue2.Client")]
public interface IClient : IDBusObject
{
    Task StartAsync();
    Task StopAsync();
    Task SetAsync(string property, object value);
    Task<IDisposable> WatchLocationUpdatedAsync(Action<(ObjectPath Old, ObjectPath New)> handler, Action<Exception>? onError = null);
}

[DBusInterface("org.freedesktop.GeoClue2.Location")]
public interface ILocation : IDBusObject
{
    Task<T> GetAsync<T>(string property);
}
#endif
