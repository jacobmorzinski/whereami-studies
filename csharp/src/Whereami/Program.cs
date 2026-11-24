// whereami — ask the operating system where you are.
// Top-level statements: this file *is* Main; `args` and `return` work as usual.
using System.Text;
using Whereami;

Console.OutputEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false);

switch (CommandLine.Parse(args))
{
    case Parsed.Info info:
        Console.Out.Write(info.Text);
        return 0;

    case Parsed.UsageError usage:
        Console.Error.Write($"Error: {usage.Message}\n");
        return 2;

    case Parsed.Ok ok:
        var outcome = await App.RunAsync(ok.Request, Platforms.Native(), App.Timeout);
        Console.Out.Write(outcome.Stdout);
        Console.Error.Write(outcome.Stderr);
        return outcome.ExitCode;

    default:
        throw new InvalidOperationException("unreachable");
}
