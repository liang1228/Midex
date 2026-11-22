#!/bin/sh
# Execute the real trial entry points with isolated system-command substitutes.
set -eu
TRIAL=${1:?Usage: verify_restart_guard.sh /absolute/path/to/trial.sh}
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT HUP INT TERM
mkdir "$TESTDIR/bin"
SH=$(command -v sh)
for tool in getprop id readlink sha256sum mutate; do
    printf '#!%s\n' "$SH" > "$TESTDIR/bin/$tool"
done
cat >> "$TESTDIR/bin/getprop" <<'EOF'
case "$1" in
    ro.product.device) echo violin ;;
    ro.build.fingerprint) echo Xiaomi/violin/violin:17/CP2A.260605.016/OS4.0.6.0.XOTCNXM:user/release-keys ;;
    sys.boot.reason) printf '%s\n' "$TEST_REASON" ;;
    crashrecovery.rescue_boot_count) printf '%s\n' "$TEST_COUNT" ;;
    sys.rescue_boot_count) printf '%s\n' "$TEST_LEGACY" ;;
esac
EOF
cat >> "$TESTDIR/bin/id" <<'EOF'
echo 0
EOF
cat >> "$TESTDIR/bin/readlink" <<'EOF'
echo 'mnt:[1]'
EOF
cat >> "$TESTDIR/bin/sha256sum" <<'EOF'
[ "$1" = /vendor/lib64/libxdmcore.so ] || exit 1
echo 'bde2d398144ba20fd0772f48add5e0787fedb8bc27b13f85721f430efff77e85  /vendor/lib64/libxdmcore.so'
EOF
cat >> "$TESTDIR/bin/mutate" <<'EOF'
printf '%s %s\n' "${0##*/}" "$*" >> "$TEST_CALLS"
case "${0##*/}" in rm|rmdir) exit 0 ;; *) exit 99 ;; esac
EOF
chmod +x "$TESTDIR/bin/"*
for tool in mount setprop mkdir chmod chcon rm rmdir; do
    cp "$TESTDIR/bin/mutate" "$TESTDIR/bin/$tool"
done
CASES=0
run_case() {
    name=$1 expected=$2 action=$3 reason=$4 count=$5 legacy=$6
    : > "$TESTDIR/calls"
    if env PATH="$TESTDIR/bin:$PATH" TEST_REASON="$reason" TEST_COUNT="$count" \
        TEST_LEGACY="$legacy" TEST_CALLS="$TESTDIR/calls" \
        "$SH" "$TRIAL" "$action" prefer165 > "$TESTDIR/output" 2>&1; then
        status=0
    else
        status=$?
    fi
    if { [ "$expected" = allow ] && [ "$status" -ne 0 ]; } || \
        { [ "$expected" = deny ] && [ "$status" -eq 0 ]; }; then
        echo "FAIL: $name (exit $status)" >&2
        cat "$TESTDIR/output" >&2
        exit 1
    fi
    if [ "$action" = restore ]; then
        grep -qx RESTORED "$TESTDIR/output"
        [ "$(wc -l < "$TESTDIR/calls")" -eq 2 ]
        grep -qx 'rm -f /data/local/tmp/extbridge-xring-mode-trial/active/token' "$TESTDIR/calls"
        grep -qx 'rmdir /data/local/tmp/extbridge-xring-mode-trial/active' "$TESTDIR/calls"
    else
        [ ! -s "$TESTDIR/calls" ] || {
            echo "FAIL: $name mutated system state" >&2
            cat "$TESTDIR/calls" >&2
            exit 1
        }
        if [ "$expected" = deny ]; then
            grep -Eq 'RescueParty reboot|Framework restart budget' "$TESTDIR/output"
        else
            grep -q '^RESTART_BUDGET_OK:' "$TESTDIR/output"
        fi
    fi
    CASES=$((CASES + 1))
    echo "PASS: $name"
}
run_case clean-zero allow restart-check cold 0 ''
run_case clean-one allow restart-check cold 1 ''
run_case clean-two allow restart-check cold 2 ''
run_case count-three deny restart-check cold 3 ''
run_case count-five deny restart-check cold 5 ''
run_case count-large deny restart-check cold 999999999999999999999999999999 ''
run_case missing-count deny restart-check cold '' ''
run_case malformed-count deny restart-check cold invalid ''
run_case negative-count deny restart-check cold -1 ''
run_case legacy-one allow restart-check cold '' 1
run_case legacy-exhausted deny restart-check cold '' 3
run_case current-takes-precedence deny restart-check cold 3 0
run_case current-zero allow restart-check cold 0 5
run_case rescue-reboot deny restart-check reboot,rescueparty 1 ''
run_case rescue-mixed-case deny restart-check reboot,RescueParty 1 ''
run_case start-rescue-before-mount deny start reboot,rescueparty 1 ''
run_case start-budget-before-mount deny start cold 3 ''
run_case restore-after-rescue allow restore reboot,rescueparty 5 ''
run_case restore-without-count allow restore cold '' ''
echo "RESTART_GUARD_VERIFIED: $CASES cases; mutations isolated, stock recovery allowed"
