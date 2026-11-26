#!/usr/bin/env bash
# Run spec/cases.json against one or all whereami ports (Linux counterpart of run.ps1).
#   bash conformance/run.sh --port rust
#   bash conformance/run.sh                 # all ports in ports.json
#   bash conformance/run.sh --cases-path FILE   # alternative cases (relative to the cwd)
set -u -o pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
port=all
cases_path=$root/spec/cases.json
os=linux

while [ $# -gt 0 ]; do
    case $1 in
        --port) port=${2:?--port needs a value}; shift 2 ;;
        --cases-path) cases_path=${2:?--cases-path needs a value}; shift 2 ;;
        *) echo "usage: run.sh [--port NAME|all] [--cases-path FILE]" >&2; exit 2 ;;
    esac
done

tmp=$(mktemp -d) || exit 2
trap 'rm -rf "$tmp"' EXIT
out=$tmp/out err=$tmp/err got=$tmp/got want=$tmp/want

readarray -t cases < <(jq -c '.[]' "$cases_path") || exit 2
ports_json=$root/conformance/ports.json
if [ "$port" = all ]; then
    readarray -t names < <(jq -r 'keys_unsorted[]' "$ports_json")
else
    names=("$port")
fi

# Print why the case fails (first failing check), or nothing if it passes.
# Uses the captured files $out/$err and the exit code in $1; case JSON in $2.
failure() {
    local rc=$1 case=$2 want_rc kind
    want_rc=$(jq -r '.exit' <<<"$case")
    if [ "$rc" != "$want_rc" ]; then echo "exit $rc, expected $want_rc"; return; fi
    kind=$(jq -r '(.stdout // {}) | if has("exact") then "exact" elif has("json") then "json" elif has("empty") then "empty" elif has("nonempty") then "nonempty" else "none" end' <<<"$case")
    case $kind in
        exact)
            jq -j --arg os "$os" '.stdout.exact | gsub("\\{os\\}"; $os)' <<<"$case" >"$want"
            if ! cmp -s "$out" "$want"; then
                echo "stdout $(jq -Rs . "$out"), expected $(jq -Rs . "$want")"; return
            fi ;;
        json)
            if ! jq -s -e 'length == 1' "$out" >/dev/null 2>&1; then
                echo "stdout is not JSON: $(jq -Rs . "$out")"; return
            fi
            if ! jq -n -e --slurpfile a "$out" --argjson b "$(jq -c '.stdout.json' <<<"$case")" '$a[0] == $b' >/dev/null; then
                echo "stdout JSON $(jq -Rrs 'sub("^\\s+";"") | sub("\\s+$";"")' "$out"), expected $(jq -c '.stdout.json' <<<"$case")"; return
            fi ;;
        empty)
            if [ -s "$out" ]; then echo "stdout not empty: $(jq -Rs . "$out")"; return; fi ;;
        nonempty)
            if [ ! -s "$out" ]; then echo "stdout empty"; return; fi ;;
    esac
    if jq -e 'has("stderr_contains")' <<<"$case" >/dev/null; then
        jq -j '.stderr_contains' <<<"$case" >"$want"
        if ! jq -n -e --rawfile e "$err" --rawfile n "$want" '$e | contains($n)' >/dev/null; then
            echo "stderr $(jq -Rs . "$err") lacks $(jq -Rs . "$want")"; return
        fi
    fi
}

failed_any=0
for name in "${names[@]}"; do
    if ! jq -e --arg n "$name" 'has($n)' "$ports_json" >/dev/null; then
        echo "UNKNOWN port: $name"; failed_any=1; continue
    fi
    cfg=$(jq -c --arg n "$name" '.[$n] | . + (.linux // {}) | del(.linux)' "$ports_json")
    rel=$(jq -r '.path' <<<"$cfg")
    exe=$root/$rel
    if [ ! -f "$exe" ]; then
        echo "MISSING $name: build with $(jq -r '.build' <<<"$cfg")"
        failed_any=1; continue
    fi
    features=$(jq -c '.features // {}' <<<"$cfg")
    prefix=()
    launcher=$(jq -r 'if .launcher then .launcher[0] else empty end' <<<"$cfg")
    if [ -n "$launcher" ]; then
        readarray -t prefix < <(jq -r '.launcher[1:][]' <<<"$cfg")
        prefix+=("$exe")
        if ! exe=$(command -v "$launcher") || [ ! -x "$exe" ]; then
            echo "MISSING $name: build with $(jq -r '.build' <<<"$cfg") (launcher '$launcher' not found on PATH)"
            failed_any=1; continue
        fi
    fi

    echo "== $name ($exe ${prefix[*]})"
    pass=0 fail=0 skip=0
    for case in "${cases[@]}"; do
        if [ "$(jq -r --argjson f "$features" --arg os "$os" '
            if (has("platforms") and ((.platforms | index($os)) == null)) then "skip"
            elif ((.requires // {}) | to_entries | any(.value != ($f[.key] // false))) then "skip"
            else "run" end' <<<"$case")" = skip ]; then
            skip=$((skip + 1)); continue
        fi
        cname=$(jq -r '.name' <<<"$case")
        readarray -d '' args < <(jq -j '.args[]? | ., "\u0000"' <<<"$case")
        "$exe" "${prefix[@]}" "${args[@]}" </dev/null >"$tmp/raw.out" 2>"$tmp/raw.err"
        rc=$?
        sed -z 's/\r\n/\n/g' "$tmp/raw.out" >"$out"
        sed -z 's/\r\n/\n/g' "$tmp/raw.err" >"$err"
        why=$(failure "$rc" "$case")
        if [ -n "$why" ]; then
            echo "FAIL $cname: $why"; fail=$((fail + 1))
        else
            echo "PASS $cname"; pass=$((pass + 1))
        fi
    done
    echo "$pass passed, $fail failed, $skip skipped"
    [ "$fail" -gt 0 ] && failed_any=1
done

exit "$failed_any"
