#!/bin/sh
set -eu
cd "$(dirname "$0")"
java_path=${JAVA_PATH:-java}
server_jar=${SERVER_JAR:-fabric-server-launch.jar}
min_memory=${MIN_MEMORY:-1G}
max_memory=${MAX_MEMORY:-3G}
loader=${LOADER:-auto}
forge_args=${FORGE_ARGS_FILE:-}
update_dir=config/guildquests/update
plan="$update_dir/pending-install.properties"
helper="$update_dir/installer.jar"
restart="$update_dir/restart.requested"

# Forge uses minecraftforge; NeoForge 1.21+ uses neoforged/neoforge, and 1.20.1 NeoForge used neoforged/forge.
forge_count=0
detected_forge_args=
for candidate in libraries/net/minecraftforge/forge/*/unix_args.txt; do
    if test -f "$candidate"; then
        forge_count=$((forge_count+1))
        detected_forge_args=$candidate
    fi
done
neoforge_count=0
detected_neoforge_args=
for candidate in libraries/net/neoforged/neoforge/*/unix_args.txt libraries/net/neoforged/forge/*/unix_args.txt; do
    if test -f "$candidate"; then
        neoforge_count=$((neoforge_count+1))
        detected_neoforge_args=$candidate
    fi
done

if test -n "$forge_args"; then
    test "$loader" != fabric || { echo 'FORGE_ARGS_FILE cannot be combined with LOADER=fabric.' >&2; exit 1; }
    case "$forge_args" in
        */neoforged/*) loader=neoforge ;;
        *) loader=forge ;;
    esac
fi

if test "$loader" = auto; then
    detected=
    test "$forge_count" -gt 0 && detected="$detected forge"
    test "$neoforge_count" -gt 0 && detected="$detected neoforge"
    test -f "$server_jar" && detected="$detected fabric"
    set -- $detected
    if test "$#" -gt 1; then
        echo "Multiple loaders were found ($*). Set LOADER=fabric, LOADER=forge or LOADER=neoforge." >&2
        exit 1
    fi
    loader=${1:-fabric}
fi

case "$loader" in
    forge|neoforge)
        if test -z "$forge_args"; then
            if test "$loader" = neoforge; then
                test "$neoforge_count" = 1 || { echo 'Select a NeoForge version with FORGE_ARGS_FILE.' >&2; exit 1; }
                forge_args=$detected_neoforge_args
            else
                test "$forge_count" = 1 || { echo 'Select a Forge version with FORGE_ARGS_FILE.' >&2; exit 1; }
                forge_args=$detected_forge_args
            fi
        fi
        test -f "$forge_args" || { echo "Loader argument file not found: $forge_args" >&2; exit 1; }
        ;;
    fabric) test -f "$server_jar" || { echo "Server JAR not found: $server_jar" >&2; exit 1; } ;;
    *) echo 'LOADER must be auto, fabric, forge, or neoforge.' >&2; exit 1 ;;
esac

while :; do
    if test -f "$plan"; then
        test -f "$helper" || { echo "Update installer is missing: $helper" >&2; exit 1; }
        "$java_path" -cp "$helper" dev.guildquests.update.UpdateInstaller "$plan" || exit 1
    fi
    rm -f "$restart"
    result=0
    if test "$loader" = forge || test "$loader" = neoforge; then
        set --
        if test -f user_jvm_args.txt; then set -- '@user_jvm_args.txt'; fi
        "$java_path" "$@" "-Xms$min_memory" "-Xmx$max_memory" "@$forge_args" nogui || result=$?
    else
        "$java_path" "-Xms$min_memory" "-Xmx$max_memory" -jar "$server_jar" nogui || result=$?
    fi
    test -f "$restart" || exit "$result"
    echo 'GuildQuests requested an update. Applying it before restarting...'
done
