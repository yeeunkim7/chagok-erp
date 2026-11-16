#!/bin/sh
set -eu

# Docker supplies its configured proxy through predefined build arguments.
# Neither a session CA nor proxy settings are written to the Gradle cache.
network_tmp=$(mktemp -d)
trap 'rm -rf "$network_tmp"' EXIT HUP INT TERM
java_network_opts=
if [ -s /run/secrets/proxy_ca ]; then
    cp "$JAVA_HOME/lib/security/cacerts" "$network_tmp/cacerts"
    keytool -importcert -noprompt -alias docker-session-proxy \
        -file /run/secrets/proxy_ca -keystore "$network_tmp/cacerts" \
        -storepass changeit >/dev/null 2>&1
    java_network_opts="-Djavax.net.ssl.trustStore=$network_tmp/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi

proxy_url=${HTTPS_PROXY:-${https_proxy:-}}
if [ -n "$proxy_url" ]; then
    # Cloud's configured proxy has a host and port. Refuse unsupported URLs
    # instead of putting proxy credentials into JVM command arguments.
    case "$proxy_url" in
        http://*|https://*) proxy_address=${proxy_url#*://} ;;
        *) echo 'Unsupported build proxy scheme.' >&2; exit 1 ;;
    esac
    proxy_address=${proxy_address%/}
    case "$proxy_address" in
        *@*|*/*|*\?*|*\#*) echo 'Build proxy must use a plain host:port URL.' >&2; exit 1 ;;
    esac
    proxy_host=${proxy_address%:*}
    proxy_port=${proxy_address##*:}
    case "$proxy_port" in ''|*[!0-9]*) echo 'Build proxy port is required.' >&2; exit 1 ;; esac
    [ "$proxy_host" != "$proxy_address" ] || { echo 'Build proxy port is required.' >&2; exit 1; }
    java_network_opts="$java_network_opts -Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port -Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port -Dhttp.nonProxyHosts=localhost|127.*|[::1]"
fi
JAVA_OPTS="${JAVA_OPTS:-} $java_network_opts"
GRADLE_OPTS="${GRADLE_OPTS:-} $java_network_opts"
export JAVA_OPTS GRADLE_OPTS
./gradlew --no-daemon --console=plain "$@"
