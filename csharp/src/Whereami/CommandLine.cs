using System.CommandLine;
using System.CommandLine.Help;
using System.Globalization;

namespace Whereami;

/// What the user asked for. A closed set of cases, each with its own data —
/// C#'s version of a Rust enum, matched with `switch`.
public abstract record Mode
{
    private Mode() { }

    /// Ask the OS where we are.
    public sealed record Current : Mode;

    /// Pretend we're here; no lookups at all.
    public sealed record Mock(Location Location) : Mode;

    /// Reverse-geocode these coordinates.
    public sealed record At(Location Location) : Mode;

    /// Forward-geocode this address.
    public sealed record Lookup(string Query) : Mode;
}

public sealed record Request(Mode Mode, bool Json);

/// The result of reading the command line.
public abstract record Parsed
{
    private Parsed() { }

    public sealed record Ok(Request Request) : Parsed;

    /// Help or version text: print to stdout, exit 0.
    public sealed record Info(string Text) : Parsed;

    /// Print to stderr, exit 2.
    public sealed record UsageError(string Message) : Parsed;
}

public static class CommandLine
{
    private static readonly Option<bool> JsonOption = new("--json") { Description = "Output as JSON" };

    private static readonly Option<string> MockOption = new("--mock")
    {
        Description = "Use provided coordinates instead of location services",
        HelpName = "LAT,LON",
    };

    private static readonly Option<string> AtOption = new("--at")
    {
        Description = "Look up the address at these coordinates (macOS)",
        HelpName = "LAT,LON",
    };

    // Replaces the built-in --version, which prints the assembly's
    // informational version and has no -v alias.
    private static readonly Option<bool> VersionOption = new("--version", "-v") { Description = "Show version" };

    private static readonly Argument<string[]> AddressArgument = new("address")
    {
        Description = "Look up the coordinates of an address (macOS)",
        Arity = ArgumentArity.ZeroOrMore,
    };

    private static RootCommand BuildRoot()
    {
        var root = new RootCommand(
            "Get your current location using native OS location services,\n" +
            "or look up an address or a pair of coordinates.\n\n" +
            "C# port of whereami by George Mandis: https://github.com/georgemandis/whereami");
        foreach (var builtIn in root.Options.OfType<System.CommandLine.VersionOption>().ToList())
        {
            root.Options.Remove(builtIn);
        }
        root.Options.Add(JsonOption);
        root.Options.Add(MockOption);
        root.Options.Add(AtOption);
        root.Options.Add(VersionOption);
        root.Arguments.Add(AddressArgument);
        return root;
    }

    /// Parse "LAT,LON": split at the first comma, trim spaces, and require two
    /// finite numbers in invariant culture ("." is the decimal point everywhere).
    public static bool TryParseCoord(string s, out double lat, out double lon)
    {
        lat = lon = 0;
        var comma = s.IndexOf(',');
        if (comma < 0)
        {
            return false;
        }
        return TryNumber(s[..comma], out lat) && TryNumber(s[(comma + 1)..], out lon);

        static bool TryNumber(string part, out double v) =>
            double.TryParse(part.Trim(' '), NumberStyles.Float, CultureInfo.InvariantCulture, out v)
            && double.IsFinite(v);
    }

    public static Parsed Parse(string[] args)
    {
        var root = BuildRoot();
        // No @file expansion: "@home" is an address, not a response file.
        var result = root.Parse(args, new ParserConfiguration { ResponseFileTokenReplacer = null });

        if (result.Action is HelpAction)
        {
            var help = new StringWriter();
            result.Invoke(new InvocationConfiguration { Output = help });
            return new Parsed.Info(help.ToString().ReplaceLineEndings("\n"));
        }
        if (result.Errors.Count > 0)
        {
            return new Parsed.UsageError(string.Join("; ", result.Errors.Select(e => e.Message)));
        }
        if (result.GetValue(VersionOption))
        {
            return new Parsed.Info($"whereami 0.4.0 ({Platforms.OsName})\n");
        }

        var words = result.GetValue(AddressArgument) ?? [];
        if (words.FirstOrDefault() == "completions")
        {
            return new Parsed.UsageError("completions are not available in this port");
        }
        if (words.FirstOrDefault(w => w.StartsWith('-')) is { } flag)
        {
            return new Parsed.UsageError($"unknown flag: {flag}");
        }

        Location? mock = null, at = null;
        if (result.GetValue(MockOption) is { } m)
        {
            if (!TryParseCoord(m, out var lat, out var lon))
            {
                return new Parsed.UsageError("invalid --mock format, expected --mock=LAT,LON (e.g., --mock=40.7128,-74.0060)");
            }
            mock = new Location(lat, lon, 0);
        }
        if (result.GetValue(AtOption) is { } a)
        {
            if (!TryParseCoord(a, out var lat, out var lon))
            {
                return new Parsed.UsageError("invalid --at format, expected --at=LAT,LON (e.g., --at=40.7128,-74.0060)");
            }
            if (lat is < -90 or > 90 || lon is < -180 or > 180)
            {
                return new Parsed.UsageError("--at coordinates out of range (latitude -90..90, longitude -180..180)");
            }
            at = new Location(lat, lon, 0);
        }

        var address = string.Join(' ', words).Trim();
        if (words.Length > 0 && address.Length == 0)
        {
            return new Parsed.UsageError("address is empty");
        }
        if (words.Length > 0 && (at is not null || mock is not null))
        {
            return new Parsed.UsageError("an address can't be combined with --at or --mock");
        }
        if (at is not null && mock is not null)
        {
            return new Parsed.UsageError("--at and --mock can't be used together");
        }

        Mode mode = (mock, at, words.Length > 0) switch
        {
            ({ } loc, _, _) => new Mode.Mock(loc),
            (_, { } loc, _) => new Mode.At(loc),
            (_, _, true) => new Mode.Lookup(address),
            _ => new Mode.Current(),
        };
        return new Parsed.Ok(new Request(mode, result.GetValue(JsonOption)));
    }
}
