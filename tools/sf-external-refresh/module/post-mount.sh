#!/system/bin/sh

# The current mount is already in effect. Keep the following boot disarmed.
: > "${0%/*}/skip_mount"
