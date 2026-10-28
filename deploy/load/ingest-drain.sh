#!/usr/bin/env bash
# Times how fast query-api stores a backlog of alerts. It resets the throwaway
# Compose project spaceflux-load, preloads COUNT generated events onto its
# alerts topic with query-api stopped, starts query-api, and waits until every
# event is stored. The drain rate comes from the stored rows' own received_at
# times, so polling and JVM start up do not enter it; start up is reported on
# its own. The events come from gen_alerts.py next to this script and are
# generated, not observed.
#
# Usage: deploy/load/ingest-drain.sh --count N --seed S --out FILE [--space-weather-share F]
#
# The project is always spaceflux-load: resetting it deletes its own two
# volumes and nothing else, and no other project can be named. It refuses to
# run while the real local stack (project spaceflux) has a running container,
# since both use the same host ports. It never starts ingest or the risk
# engine, so no feed is called. The project is left running for the read test.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
compose_file="$repo_root/deploy/compose.yaml"
project="spaceflux-load"
real_project="spaceflux"

die() { echo "error: $*" >&2; exit 1; }

count=""
seed=""
out=""
share="0.1"
while [ $# -gt 0 ]; do
  case "$1" in
    --count) [ $# -ge 2 ] || die "--count needs a value"; count="$2"; shift 2 ;;
    --seed) [ $# -ge 2 ] || die "--seed needs a value"; seed="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || die "--out needs a file"; out="$2"; shift 2 ;;
    --space-weather-share) [ $# -ge 2 ] || die "--space-weather-share needs a value"; share="$2"; shift 2 ;;
    *) die "unknown argument $1; see the usage at the top of $0" ;;
  esac
done
[[ "$count" =~ ^[1-9][0-9]*$ ]] && [ "$count" -ge 2 ] || die "--count must be a whole number of at least 2"
[[ "$seed" =~ ^[0-9]+$ ]] || die "--seed must be a whole number"
[[ "$share" =~ ^(0(\.[0-9]+)?|1(\.0+)?)$ ]] || die "--space-weather-share must be a decimal from 0 to 1"
[ -n "$out" ] || die "--out is required"
[[ "$out" != -* ]] || die "--out must not start with a dash"
[ ! -d "$out" ] || die "--out names a directory, not a file"
[ -d "$(dirname "$out")" ] && [ -w "$(dirname "$out")" ] || die "the directory of $out does not exist or is not writable"

compose() { docker compose -p "$project" -f "$compose_file" --profile core "$@"; }
# mysqld is the container's first process and never reaps an orphan, so a process
# substitution here would leave one zombie per call until the pid limit stops the
# container forking. A here-string runs no process of its own.
sql() {
  compose exec -T mysql bash -c \
    'mysql --defaults-extra-file=/dev/fd/3 -uroot -N -B spaceflux 3<<< "$(printf "[client]\npassword=%s\n" "$(< /run/secrets/mysql_root_password)")"'
}
kafka_tool() { compose exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "$@"; }
end_offset() {
  kafka_tool /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:19092 --topic "$1" |
    awk -F: '{s += $3} END {print s + 0}'
}

real_running="$(docker compose -p "$real_project" -f "$compose_file" --profile core ps -q)" ||
  die "could not list the containers of project $real_project"
if [ -n "$real_running" ]; then
  die "project $real_project has running or paused containers and uses the same ports; stop it first with: docker compose -p $real_project -f deploy/compose.yaml --profile core stop"
fi

# down --volumes removes every volume the file declares; a volume with a fixed name
# or marked external could be the real stack's, so each must carry this project's prefix.
volumes="$(compose config --no-interpolate --format json | python3 -I -c 'import json, sys
for v in (json.load(sys.stdin).get("volumes") or {}).values(): print(v.get("name", ""))')" ||
  die "could not list the volumes of $project"
while read -r v; do
  [[ "$v" == "${project}_"* ]] || die "volume '$v' is not owned by $project; refusing to reset"
done <<< "$volumes"

echo "resetting $project (its own volumes only)"
compose down --volumes --remove-orphans >/dev/null || die "could not reset $project"
compose build query-api migrate >/dev/null
compose up -d --wait kafka mysql >/dev/null
compose run --rm topics >/dev/null
compose run --rm migrate >/dev/null

echo "preloading $count events onto alerts"
python3 -I "$script_dir/gen_alerts.py" --count "$count" --seed "$seed" --space-weather-share "$share" |
  kafka_tool /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic alerts \
    --reader-property parse.key=true
preloaded="$(end_offset alerts)"
[ "$preloaded" = "$count" ] || die "alerts holds $preloaded records after the preload, not $count"

echo "starting query-api"
load_start="$(cut -d' ' -f1-3 /proc/loadavg)"
started="$(date -u +%Y-%m-%dT%H:%M:%S.%6NZ)"
compose up -d --no-deps query-api >/dev/null

deadline=$(( $(date +%s) + 1800 ))
stored=0
polls=0
while :; do
  stored="$(echo "SELECT COUNT(*) FROM alert_event;" | sql)"
  [ "$stored" = "$count" ] && break
  [ -n "$(compose ps -q --status running query-api)" ] || die "query-api stopped with $stored of $count events stored"
  # Each Kafka tool call starts a JVM next to the drain, so the dead letter check runs only every 15 polls.
  polls=$((polls + 1))
  if [ $((polls % 15)) = 0 ]; then
    rejected="$(end_offset alerts.dlq)"
    [ "$rejected" = "0" ] || die "query-api sent $rejected events to alerts.dlq; $stored of $count stored"
  fi
  [ "$(date +%s)" -lt "$deadline" ] || die "only $stored of $count events stored after 30 minutes"
  sleep 1
done
lag="$(kafka_tool /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --describe \
  --group query-api-alerts 2>/dev/null |
  awk '$2 == "alerts" && $6 ~ /^[0-9]+$/ {s += $6; n++} END {print (n ? s : "unknown")}')"
rejected="$(end_offset alerts.dlq)"
load_end="$(cut -d' ' -f1-3 /proc/loadavg)"

read -r first last drain < <(echo "SELECT DATE_FORMAT(MIN(received_at), '%Y-%m-%dT%H:%i:%s.%fZ'),
  DATE_FORMAT(MAX(received_at), '%Y-%m-%dT%H:%i:%s.%fZ'),
  TIMESTAMPDIFF(MICROSECOND, MIN(received_at), MAX(received_at)) / 1000000 FROM alert_event;" | sql)
[ -n "$first" ] && [ -n "$last" ] && [ -n "$drain" ] || die "could not read the stored times"
startup="$(python3 -I -c 'import sys
from datetime import datetime
f = "%Y-%m-%dT%H:%M:%S.%fZ"
print("%.3f" % (datetime.strptime(sys.argv[2], f) - datetime.strptime(sys.argv[1], f)).total_seconds())' \
  "$started" "$first")"
rate="$(awk -v n="$count" -v d="$drain" 'BEGIN {if (d > 0) printf "%.1f", (n - 1) / d; else print "n/a"}')"
kinds="$(echo "SELECT kind, COUNT(*) FROM alert_event GROUP BY kind ORDER BY kind;" | sql | tr '\t' '=' | paste -sd' ')"

finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
commit="$(git -C "$repo_root" rev-parse --short HEAD)"
dirty="$(git -C "$repo_root" status --porcelain)"
cpu="$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
processors="$(nproc)"
memory="$(awk '/MemTotal/ {printf "%.1f GiB", $2 / 1048576}' /proc/meminfo)"
docker_version="$(docker version --format '{{.Server.Version}}')"
images=""
for s in kafka mysql query-api; do
  id="$(compose ps -q "$s")"
  images+="$s=$(docker inspect --format '{{.Config.Image}} {{.Image}}' "$id") "
done

tmp="$(mktemp "$(dirname "$out")/.ingest-drain.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
{
  echo "query-api ingest drain, local Compose project $project, $finished"
  echo "commit $commit$([ -z "$dirty" ] || echo ' with uncommitted changes')"
  echo "cpu $cpu, $processors processors, memory $memory"
  echo "docker $docker_version, images: ${images% }"
  echo "load average just before query-api started $load_start, at end $load_end"
  echo "dataset: $count generated events from gen_alerts.py, seed $seed, space weather share $share, one partition"
  echo "stored $stored ($kinds), sent to alerts.dlq $rejected, consumer lag after the drain $lag"
  echo "query-api started $started, first stored $first, last stored $last"
  echo "start up to the first stored event: $startup s"
  echo "drain, first to last stored: $drain s, $rate events per second"
} > "$tmp"
chmod 644 "$tmp"
mv -T -- "$tmp" "$out"
trap - EXIT
echo "wrote $out"
cat -- "$out"
