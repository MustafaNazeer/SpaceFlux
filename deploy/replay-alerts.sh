#!/usr/bin/env bash
# Replays committed example alerts onto the alerts topic of the local Compose
# stack, so a new alert can be stored on demand for checks by hand (the live
# alert subscription, and checks M9 and M10 in docs/a11y/wcag-checklist.md).
# For the local stack only: it writes through the console producer inside the
# running kafka container and has no way to reach any other broker.
#
# Usage: deploy/replay-alerts.sh --project NAME [--real-stack] [--dry-run] MODE [ARG]
#
#   status            lists the fixtures and whether each event_id is stored
#   one [FIXTURE]     sends the first fixture not yet stored, or FIXTURE
#   burst [COUNT]     sends the next COUNT fixtures not yet stored (default 3)
#                     in one producer run, so they reach the topic together
#   again [FIXTURE]   sends a fixture that is already stored (FIXTURE, or the
#                     one stored last), which query-api drops as a repeat
#
#   --project NAME  the Compose project to send to, required. Use a throwaway
#                   project such as spaceflux-check (see docs/setup-guide.md)
#   --real-stack    needed as well to send to spaceflux, the project of my real
#                   local stack, whose stored alerts would then keep these rows
#   --dry-run       prints each key and value it would send and touches nothing
#
# The fixtures are files in schemas/alerts/examples/, whose provenance is in
# docs/data/topics.md under "Examples". Each is sent byte for byte, with only
# its line breaks removed, keyed as the risk engine keys it (ADR 0007): the
# run_id for a close approach, space_weather.<scale> for a space weather event.
# No headers are set; the risk engine sets none either.
#
# query-api stores each event_id once, so a fixture can be replayed as a new
# alert only once per database. This script never deletes or rewrites anything:
# when every fixture is stored it stops and says so.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
compose_file="$script_dir/compose.yaml"
examples_dir="$repo_root/schemas/alerts/examples"

# Only fixtures the recent alerts list shows. The two G examples are left out on
# purpose: storing a level change also sets its series' current state, and the
# G scale has one series, so a replayed 2024 Kp value would become the current
# G level on the dashboard. The R and S examples are from GOES-16 and GOES-13,
# whose series are separate from the current GOES satellites' series. Storing
# them does create those two old series, so the dashboard then shows each one
# as stale, which is expected on a throwaway project.
pool=(
  valid-close-approach.json
  valid-r-level.json
  valid-s-level.json
)

die() { echo "error: $*" >&2; exit 1; }

# The name: line of compose.yaml, the project my real local stack runs under.
real_project="spaceflux"

dry_run=0
real_stack=0
project=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry_run=1; shift ;;
    --real-stack) real_stack=1; shift ;;
    --project)
      [ $# -ge 2 ] || die "--project needs a name"
      project="$2"; shift 2 ;;
    --*) die "unknown option $1; see the usage at the top of $0" ;;
    *) break ;;
  esac
done
mode="${1:-}"
arg="${2:-}"
[ $# -le 2 ] || die "too many arguments; see the usage at the top of $0"

[ -n "$project" ] || die "--project is required, for example --project spaceflux-check; see docs/setup-guide.md"
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "a Compose project name is lowercase letters, digits, dashes and underscores"
if [ "$project" = "$real_project" ] && [ "$real_stack" = 0 ]; then
  die "$real_project is the project of the real local stack; add --real-stack to send to it on purpose"
fi

command -v jq >/dev/null || die "jq is required"

# -p takes precedence over the name: line and over COMPOSE_PROJECT_NAME.
compose() { docker compose -p "$project" -f "$compose_file" --profile core "$@"; }

in_pool() {
  local f
  for f in "${pool[@]}"; do
    [ "$f" = "$1" ] && return 0
  done
  return 1
}

# One line of JSON, the file's own bytes without its line breaks.
value_of() {
  local file="$examples_dir/$1" line
  [ -f "$file" ] || die "no fixture $file"
  if grep -q $'[\t\r]' "$file"; then
    die "$1 holds a tab or carriage return, which the producer line format cannot carry"
  fi
  line="$(tr -d '\n' < "$file")"
  printf '%s' "$line" | jq -e . >/dev/null || die "$1 is not one JSON value once its line breaks are removed"
  printf '%s' "$line"
}

event_id_of() {
  local id
  id="$(value_of "$1" | jq -r '.event_id')"
  [[ "$id" =~ ^[A-Za-z0-9/:._-]+$ ]] || die "$1 has an event_id this script does not expect: $id"
  printf '%s' "$id"
}

key_of() {
  value_of "$1" | jq -r '
    if .kind == "close_approach" then .close_approach.run_id
    elif .kind == "space_weather_level" then "space_weather." + .space_weather_level.scale
    else error("kind \(.kind) is not listed by the recent alerts list") end'
}

require_running() {
  [ -n "$(compose ps -q --status running kafka)" ] || die "the kafka service of project $project is not running"
  [ -n "$(compose ps -q --status running mysql)" ] || die "the mysql service of project $project is not running"
}

# Read only: prints the stored event_ids among the pool, newest first. The root
# password is read from its secret file inside the container, never put on a
# command line, as in docs/setup-guide.md.
stored_ids() {
  local f ids=()
  for f in "${pool[@]}"; do
    ids+=("'$(event_id_of "$f")'")
  done
  local list
  list="$(IFS=,; printf '%s' "${ids[*]}")"
  printf 'SELECT event_id FROM alert_event WHERE event_id IN (%s) ORDER BY alert_seq DESC;\n' "$list" |
    compose exec -T mysql bash -c \
      'mysql --defaults-extra-file=/dev/fd/3 -uroot -N -B spaceflux 3<<< "$(printf "[client]\npassword=%s\n" "$(< /run/secrets/mysql_root_password)")"'
}

is_stored() {
  local id="$1" s
  while IFS= read -r s; do
    [ "$s" = "$id" ] && return 0
  done <<< "$stored"
  return 1
}

send() {
  local f lines=""
  for f in "$@"; do
    lines+="$(key_of "$f")"$'\t'"$(value_of "$f")"$'\n'
  done
  if [ "$dry_run" = 1 ]; then
    echo "dry run: would send ${#} record(s) to alerts in project $project, each as key<TAB>value:"
    printf '%s' "$lines"
    return
  fi
  printf '%s' "$lines" |
    compose exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server kafka:19092 --topic alerts --reader-property parse.key=true
  echo "sent ${#} record(s) to alerts in project $project at $(date -u +%H:%M:%SZ): $*"
}

pick_unstored() {
  local want="$1" f picked=()
  for f in "${pool[@]}"; do
    [ "${#picked[@]}" -lt "$want" ] || break
    is_stored "$(event_id_of "$f")" || picked+=("$f")
  done
  if [ "${#picked[@]}" -lt "$want" ]; then
    die "only ${#picked[@]} of the ${#pool[@]} fixtures are not yet stored, and $want are needed. A fixture already stored is dropped as a repeat and never pushed, so it cannot stand in for a new alert. Run '$0 --project $project status' to see which are stored."
  fi
  printf '%s\n' "${picked[@]}"
}

if [ "$dry_run" = 1 ]; then
  stored=""
  echo "dry run: the stored check is skipped, so every fixture counts as not yet stored"
else
  case "$mode" in
    status|one|burst|again) require_running; stored="$(stored_ids)" ;;
  esac
fi

case "$mode" in
  status)
    for f in "${pool[@]}"; do
      id="$(event_id_of "$f")"
      if is_stored "$id"; then s="stored"; else s="not stored"; fi
      printf '%-28s %-10s key %s\n' "$f" "$s" "$(key_of "$f")"
    done
    ;;
  one)
    if [ -n "$arg" ]; then
      in_pool "$arg" || die "$arg is not one of: ${pool[*]}"
      if is_stored "$(event_id_of "$arg")"; then
        die "$arg is already stored, so it would be a repeat; use 'again' to send a repeat on purpose"
      fi
      send "$arg"
    else
      picked="$(pick_unstored 1)"
      send "$picked"
    fi
    ;;
  burst)
    count="${arg:-3}"
    [[ "$count" =~ ^[1-9][0-9]*$ ]] || die "COUNT must be a whole number of 1 or more"
    picked="$(pick_unstored "$count")"
    mapfile -t files <<< "$picked"
    send "${files[@]}"
    ;;
  again)
    if [ -n "$arg" ]; then
      in_pool "$arg" || die "$arg is not one of: ${pool[*]}"
      f="$arg"
    elif [ "$dry_run" = 1 ]; then
      f="${pool[0]}"
    else
      newest="$(head -n 1 <<< "$stored")"
      [ -n "$newest" ] || die "no fixture is stored yet, so there is nothing to repeat"
      f=""
      for g in "${pool[@]}"; do
        [ "$(event_id_of "$g")" = "$newest" ] && f="$g"
      done
    fi
    if [ "$dry_run" = 0 ] && ! is_stored "$(event_id_of "$f")"; then
      die "$f is not stored yet, so it would be stored as a new alert, not a repeat"
    fi
    send "$f"
    ;;
  *)
    die "unknown mode '${mode}'; use status, one, burst or again (see the top of $0)"
    ;;
esac
