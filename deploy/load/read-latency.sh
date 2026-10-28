#!/usr/bin/env bash
# Measures query-api read latency under steady load with k6 (read-latency.js
# next to this script), against the database ingest-drain.sh left in the
# throwaway Compose project spaceflux-load. Every event_id stored there is
# handed to k6 for the by id reads. The events are generated, not observed.
#
# Usage: deploy/load/read-latency.sh --drain FILE --out FILE [--rates 20,50,100] [--duration 60] [--warmup 15]
#
#   --drain FILE  the results file of the ingest-drain.sh run that loaded the
#                 database; its stored count must match, and its dataset line
#                 (count and seed) is copied into the results
#
# It only reads: it never starts, stops or resets a project. It refuses to run
# while the real local stack (project spaceflux) has a running or paused container, since
# both answer on the same host port, and it needs spaceflux-load's query-api
# running, before and after the run. k6 runs from an image pinned by digest, on
# the host network, with every capability dropped.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
compose_file="$repo_root/deploy/compose.yaml"
project="spaceflux-load"
real_project="spaceflux"
k6_image="grafana/k6:2.2.0@sha256:9bd01d6941fca969cb61bb57d2da5ee9b385fe2aa8881df3798c196564d6ace6"
base_url="http://127.0.0.1:8081"

die() { echo "error: $*" >&2; exit 1; }

drain=""
out=""
warmup="15"
rates="20,50,100"
duration="60"
while [ $# -gt 0 ]; do
  case "$1" in
    --drain) [ $# -ge 2 ] || die "--drain needs a file"; drain="$2"; shift 2 ;;
    --warmup) [ $# -ge 2 ] || die "--warmup needs a value"; warmup="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || die "--out needs a file"; out="$2"; shift 2 ;;
    --rates) [ $# -ge 2 ] || die "--rates needs a value"; rates="$2"; shift 2 ;;
    --duration) [ $# -ge 2 ] || die "--duration needs a value"; duration="$2"; shift 2 ;;
    *) die "unknown argument $1; see the usage at the top of $0" ;;
  esac
done
[ -n "$out" ] || die "--out is required"
[[ "$out" != -* ]] || die "--out must not start with a dash"
[ ! -d "$out" ] || die "--out names a directory, not a file"
[ -d "$(dirname "$out")" ] && [ -w "$(dirname "$out")" ] || die "the directory of $out does not exist or is not writable"
[[ "$rates" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] || die "--rates must be positive whole numbers separated by commas"
[[ "$duration" =~ ^[1-9][0-9]*$ ]] || die "--duration must be a positive whole number of seconds"
[[ "$warmup" =~ ^[1-9][0-9]*$ ]] || die "--warmup must be a positive whole number of seconds"
[ -n "$drain" ] || die "--drain is required"
[ -f "$drain" ] || die "--drain $drain is not a file"
drain_dataset="$(grep -m1 '^dataset: ' -- "$drain")" || die "$drain has no dataset line"
drain_stored="$(sed -n 's/^stored \([0-9][0-9]*\) (.*/\1/p' -- "$drain" | head -n1)"
[ -n "$drain_stored" ] || die "$drain has no stored count"

compose() { docker compose -p "$project" -f "$compose_file" --profile core "$@"; }
sql() {
  compose exec -T mysql bash -c \
    'mysql --defaults-extra-file=<(printf "[client]\npassword=%s\n" "$(< /run/secrets/mysql_root_password)") -uroot -N -B spaceflux'
}

# The checks run before and after k6, so numbers are never written for a stack
# that changed under the run.
check_target() {
  local real_running running_api
  real_running="$(docker compose -p "$real_project" -f "$compose_file" --profile core ps -q)" ||
    die "could not list the containers of project $real_project"
  [ -z "$real_running" ] ||
    die "project $real_project has running or paused containers and uses the same ports; stop it first with: docker compose -p $real_project -f deploy/compose.yaml --profile core stop"
  running_api="$(compose ps -q --status running query-api)" || die "could not list the containers of $project"
  [ -n "$running_api" ] || die "query-api of $project is not running; run deploy/load/ingest-drain.sh first"
  [ -z "$1" ] || [ "$running_api" = "$1" ] || die "query-api of $project changed during the run"
  load_api="$running_api"
}
check_target ""

work="$(mktemp -d)"
tmp=""
trap 'rm -rf "$work"; [ -z "$tmp" ] || rm -f "$tmp"' EXIT
echo "SELECT event_id FROM alert_event ORDER BY alert_seq;" | sql > "$work/ids.txt" ||
  die "could not read the stored event ids from $project"
stored="$(wc -l < "$work/ids.txt")"
[ "$stored" -gt 0 ] || die "$project stores no alerts; run deploy/load/ingest-drain.sh first"
[ "$stored" = "$drain_stored" ] || die "$project stores $stored events but $drain says $drain_stored"
kinds="$(echo "SELECT kind, COUNT(*) FROM alert_event GROUP BY kind ORDER BY kind;" | sql | tr '\t' '=' | paste -sd' ')" ||
  die "could not count the stored events of $project"

load_start="$(cut -d' ' -f1-3 /proc/loadavg)"
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "warming up for $warmup s, then reading at $rates requests per second, $duration s each, from $stored stored events"
docker run --rm --network host -u "$(id -u):$(id -g)" --cap-drop ALL --security-opt no-new-privileges \
  --mount "type=bind,src=$script_dir/read-latency.js,dst=/scripts/read-latency.js,readonly" \
  --mount "type=bind,src=$work,dst=/work" \
  "$k6_image" run --quiet --no-usage-report --include-system-env-vars=false \
  -e BASE_URL="$base_url" -e RATES="$rates" -e DURATION="$duration" -e WARMUP="$warmup" \
  -e IDS_FILE=/work/ids.txt -e SUMMARY_FILE=/work/summary.json \
  /scripts/read-latency.js >&2
load_end="$(cut -d' ' -f1-3 /proc/loadavg)"
[ -s "$work/summary.json" ] || die "k6 wrote no summary"
check_target "$load_api"

commit="$(git -C "$repo_root" rev-parse --short HEAD)"
dirty="$(git -C "$repo_root" status --porcelain)"
cpu="$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
processors="$(nproc)"
memory="$(awk '/MemTotal/ {printf "%.1f GiB", $2 / 1048576}' /proc/meminfo)"
docker_version="$(docker version --format '{{.Server.Version}}')"
images=""
for s in mysql query-api; do
  id="$(compose ps -q "$s")"
  images+="$s=$(docker inspect --format '{{.Config.Image}} {{.Image}}' "$id") "
done

tmp="$(mktemp "$(dirname "$out")/.read-latency.XXXXXX")"
{
  echo "query-api read latency, local Compose project $project, started $started"
  echo "commit $commit$([ -z "$dirty" ] || echo ' with uncommitted changes')"
  echo "cpu $cpu, $processors processors, memory $memory"
  echo "docker $docker_version, k6 $k6_image, images: ${images% }"
  echo "load average at start $load_start, at end $load_end"
  echo "dataset: $stored stored generated events ($kinds), loaded by the ingest-drain.sh run in $(basename -- "$drain")"
  echo "that run's $drain_dataset"
  echo "load: $warmup s warm up at the lowest rate, not reported; then constant arrival rate at $rates requests per second, $duration s each, one after another; equal thirds of GraphQL recent alerts (20), REST alert by id, REST current space weather; 4 s request timeout"
  cat "$work/summary.json"
} > "$tmp"
chmod 644 "$tmp"
mv -T -- "$tmp" "$out"
tmp=""
echo "wrote $out"
cat -- "$out"
