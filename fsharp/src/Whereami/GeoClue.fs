/// GeoClue2's D-Bus interfaces, declared as annotated F# interfaces. Tmds.DBus
/// generates the proxy classes from these at run time (hence public: the
/// generated assembly has to see them), so there is no hand-written marshalling.
namespace Whereami.GeoClue

#if !WINDOWS
open System
open System.Threading.Tasks
open Tmds.DBus

module Names =
    [<Literal>]
    let Service = "org.freedesktop.GeoClue2"

    let ManagerPath = ObjectPath "/org/freedesktop/GeoClue2/Manager"

[<DBusInterface("org.freedesktop.GeoClue2.Manager")>]
type IManager =
    inherit IDBusObject
    abstract CreateClientAsync: unit -> Task<ObjectPath>

[<DBusInterface("org.freedesktop.GeoClue2.Client")>]
type IClient =
    inherit IDBusObject
    abstract StartAsync: unit -> Task
    abstract StopAsync: unit -> Task
    abstract SetAsync: property: string * value: obj -> Task
    abstract WatchLocationUpdatedAsync:
        handler: Action<struct (ObjectPath * ObjectPath)> * onError: Action<exn> -> Task<IDisposable>

[<DBusInterface("org.freedesktop.GeoClue2.Location")>]
type ILocation =
    inherit IDBusObject
    abstract GetAsync<'T> : property: string -> Task<'T>
#endif
