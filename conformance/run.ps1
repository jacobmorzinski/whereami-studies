#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Run spec/cases.json against one or all whereami ports.
.EXAMPLE
  pwsh conformance/run.ps1 -Port rust
  pwsh conformance/run.ps1            # all ports in ports.json
#>
param(
    [string]$Port = 'all',
    # Alternative cases file, e.g. to check that the runner itself catches mistakes.
    [string]$CasesPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $CasesPath) { $CasesPath = Join-Path $root 'spec/cases.json' }
$cases = Get-Content -Raw $CasesPath | ConvertFrom-Json -AsHashtable
$ports = Get-Content -Raw (Join-Path $PSScriptRoot 'ports.json') | ConvertFrom-Json -AsHashtable

$os = if ($IsWindows) { 'windows' } elseif ($IsMacOS) { 'macos' } else { 'linux' }

function Test-JsonEqual($a, $b) {
    if ($null -eq $a -or $null -eq $b) { return ($null -eq $a) -and ($null -eq $b) }
    $numeric = { param($v) $v -is [int] -or $v -is [long] -or $v -is [double] -or $v -is [decimal] -or $v -is [System.Numerics.BigInteger] }
    if ((& $numeric $a) -and (& $numeric $b)) { return [double]$a -eq [double]$b }
    if ($a -is [System.Collections.IDictionary] -and $b -is [System.Collections.IDictionary]) {
        if ($a.Count -ne $b.Count) { return $false }
        foreach ($k in $a.Keys) {
            if (-not $b.Contains($k)) { return $false }
            if (-not (Test-JsonEqual $a[$k] $b[$k])) { return $false }
        }
        return $true
    }
    if ($a -is [System.Collections.IList] -and $b -is [System.Collections.IList]) {
        if ($a.Count -ne $b.Count) { return $false }
        for ($i = 0; $i -lt $a.Count; $i++) { if (-not (Test-JsonEqual $a[$i] $b[$i])) { return $false } }
        return $true
    }
    if ($a.GetType() -ne $b.GetType()) { return $false }
    return $a -ceq $b   # -eq ignores case for strings
}

function Invoke-Port([string]$exe, [string[]]$arguments) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new($exe)
    foreach ($a in $arguments) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $p = [System.Diagnostics.Process]::Start($psi)
    $errTask = $p.StandardError.ReadToEndAsync()
    $out = $p.StandardOutput.ReadToEnd()
    $p.WaitForExit()
    [pscustomobject]@{ Exit = $p.ExitCode; Stdout = $out -replace "`r`n", "`n"; Stderr = $errTask.Result -replace "`r`n", "`n" }
}

function Get-Failure($case, $r) {
    if ($r.Exit -ne $case.exit) { return "exit $($r.Exit), expected $($case.exit)" }
    $s = $case.stdout
    if ($s) {
        if ($s.Contains('exact')) {
            $want = $s.exact -replace '\{os\}', $os
            if ($r.Stdout -cne $want) { return "stdout $(ConvertTo-Json $r.Stdout), expected $(ConvertTo-Json $want)" }
        } elseif ($s.Contains('json')) {
            try { $got = $r.Stdout | ConvertFrom-Json -AsHashtable } catch { return "stdout is not JSON: $(ConvertTo-Json $r.Stdout)" }
            if (-not (Test-JsonEqual $got $s.json)) { return "stdout JSON $($r.Stdout.Trim()), expected $(ConvertTo-Json $s.json -Compress)" }
        } elseif ($s.Contains('empty')) {
            if ($r.Stdout -ne '') { return "stdout not empty: $(ConvertTo-Json $r.Stdout)" }
        } elseif ($s.Contains('nonempty')) {
            if ($r.Stdout -eq '') { return 'stdout empty' }
        }
    }
    if ($case.Contains('stderr_contains') -and -not $r.Stderr.Contains($case.stderr_contains)) {
        return "stderr $(ConvertTo-Json $r.Stderr) lacks $(ConvertTo-Json $case.stderr_contains)"
    }
    return $null
}

$names = if ($Port -eq 'all') { @($ports.Keys) } else { @($Port) }
$failedAny = $false

foreach ($name in $names) {
    if (-not $ports.Contains($name)) { Write-Host "UNKNOWN port: $name"; $failedAny = $true; continue }
    $cfg = $ports[$name]
    $exe = Join-Path $root $cfg.path
    if ($IsWindows -and (Test-Path "$exe.exe")) { $exe = "$exe.exe" }
    elseif ($IsWindows -and (Test-Path "$exe.bat")) { $exe = "$exe.bat" }  # e.g. Gradle start scripts
    if (-not (Test-Path $exe -PathType Leaf)) {
        Write-Host "MISSING ${name}: build with $($cfg.build)"
        $failedAny = $true
        continue
    }
    # A port whose build output isn't itself executable (e.g. a jar) names a
    # launcher: run launcher[0] with launcher[1..], then the path, then the case args.
    $prefix = @()
    if ($cfg.launcher) {
        $prefix = @($cfg.launcher | Select-Object -Skip 1) + $exe
        $exe = (Get-Command $cfg.launcher[0] -CommandType Application | Select-Object -First 1).Source
    }

    Write-Host "== $name ($exe $prefix)"
    $pass = 0; $fail = 0; $skip = 0
    foreach ($case in $cases) {
        if ($case.Contains('platforms') -and $os -notin $case.platforms) { $skip++; continue }
        $unmet = $false
        if ($case.Contains('requires')) {
            foreach ($k in $case.requires.Keys) {
                $have = if ($cfg.features -and $cfg.features.Contains($k)) { $cfg.features[$k] } else { $false }
                if ($have -ne $case.requires[$k]) { $unmet = $true }
            }
        }
        if ($unmet) { $skip++; continue }

        $why = Get-Failure $case (Invoke-Port $exe ([string[]](@($prefix) + @($case.args))))
        if ($why) { Write-Host "FAIL $($case.name): $why"; $fail++ } else { Write-Host "PASS $($case.name)"; $pass++ }
    }
    Write-Host "$pass passed, $fail failed, $skip skipped"
    if ($fail -gt 0) { $failedAny = $true }
}

if ($failedAny) { exit 1 }
