#!/usr/bin/env bash
# Samples the memory of every running container in the spaceflux Compose
# project and prints per container min, median and max.
#
# Usage: deploy/measure-ram.sh [duration_seconds] [interval_seconds] [output_dir]
#   duration_seconds  default 360 (spans at least one 5 minute SWPC poll)
#   interval_seconds  default 5
#   output_dir        default docs/perf/data under the repository root
#
# Source: the cgroup v2 files of each container, read directly on the host
# (memory.current, memory.stat, memory.swap.current, memory.peak,
# pids.current). The reported "usage" is memory.current minus inactive_file,
# which is the figure docker stats shows on cgroup v2. The script checks that
# equivalence against docker stats once at the start and once at the end.
# memory.current does not count pages a container has swapped out, so swap is
# sampled too and "usage+swap" is reported beside usage.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

duration="${1:-360}"
interval="${2:-5}"
out_dir="${3:-$repo_root/docs/perf/data}"
project="spaceflux"

# 63677270 is CGROUP2_SUPER_MAGIC; some stat builds print it as UNKNOWN for %T.
if [ "$(stat -fc %t /sys/fs/cgroup)" != "63677270" ]; then
  echo "error: /sys/fs/cgroup is not cgroup v2; this script reads cgroup v2 files" >&2
  exit 1
fi

mapfile -t names < <(docker ps --filter "label=com.docker.compose.project=$project" --format '{{.Names}}' | sort)
if [ "${#names[@]}" -eq 0 ]; then
  echo "error: no running containers in Compose project $project" >&2
  exit 1
fi

declare -A cg pid
for n in "${names[@]}"; do
  p="$(docker inspect -f '{{.State.Pid}}' "$n")"
  pid[$n]="$p"
  rel="$(awk -F: '$1 == "0" { print $3 }' "/proc/$p/cgroup")"
  cg[$n]="/sys/fs/cgroup$rel"
  [ -r "${cg[$n]}/memory.current" ] || { echo "error: cannot read ${cg[$n]}/memory.current" >&2; exit 1; }
done

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$out_dir"
samples="$out_dir/local-memory-$stamp.tsv"
summary="$out_dir/local-memory-$stamp.summary.txt"

stat_of() { awk -v k="$2" '$1 == k { print $2 }' "$1/memory.stat"; }

docker_stats_check() {
  echo "cross check at $(date -u +%FT%TZ): cgroup usage read before and after docker stats"
  local n c ds before after
  for n in "${names[@]}"; do
    c="${cg[$n]}"
    before=$(( $(cat "$c/memory.current") - $(stat_of "$c" inactive_file) ))
    ds="$(docker stats --no-stream --format '{{.MemUsage}}' "$n")"
    after=$(( $(cat "$c/memory.current") - $(stat_of "$c" inactive_file) ))
    printf '  %s cgroup_before=%.2fMiB docker_stats=%s cgroup_after=%.2fMiB\n' "$n" \
      "$(awk -v b="$before" 'BEGIN { print b / 1048576 }')" "$ds" "$(awk -v b="$after" 'BEGIN { print b / 1048576 }')"
  done
}

{
  echo "# spaceflux memory samples, started $(date -u +%FT%TZ), duration ${duration}s, interval ${interval}s"
  printf 'epoch\tcontainer\tusage_bytes\tcurrent_bytes\tanon_bytes\tfile_bytes\tinactive_file_bytes\tswap_bytes\tpids\trx_bytes\ttx_bytes\n'
} > "$samples"

check_start="$(docker_stats_check)"

end=$(( $(date +%s) + duration ))
while [ "$(date +%s)" -lt "$end" ]; do
  now="$(date +%s)"
  total=0
  total_swap=0
  for n in "${names[@]}"; do
    c="${cg[$n]}"
    if [[ ! -r "$c/memory.current" ]]; then
      echo "skipping $n at $now: its cgroup is gone (container restarted?)" >&2
      continue
    fi
    cur="$(cat "$c/memory.current")"
    anon="$(stat_of "$c" anon)"
    file="$(stat_of "$c" file)"
    inact="$(stat_of "$c" inactive_file)"
    swap="$(cat "$c/memory.swap.current")"
    pids="$(cat "$c/pids.current")"
    rx=0 tx=0
    read -r rx tx < <(awk '$1 == "eth0:" { print $2, $10 }' "/proc/${pid[$n]}/net/dev" 2>/dev/null) || true
    usage=$(( cur - inact ))
    total=$(( total + usage ))
    total_swap=$(( total_swap + swap ))
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$now" "$n" "$usage" "$cur" "$anon" "$file" "$inact" "$swap" "$pids" "${rx:-0}" "${tx:-0}" >> "$samples"
  done
  read -r host_used host_avail < <(free -b | awk '$1 == "Mem:" { print $3, $7 }')
  read -r host_swap_used < <(free -b | awk '$1 == "Swap:" { print $3 }')
  printf '%s\t_containers_total\t%s\t\t\t\t\t%s\t\t\t\n' "$now" "$total" "$total_swap" >> "$samples"
  printf '%s\t_host_used\t%s\t\t\t\t\t%s\t\t\t\n' "$now" "$host_used" "$host_swap_used" >> "$samples"
  printf '%s\t_host_available\t%s\t\t\t\t\t\t\t\t\n' "$now" "$host_avail" >> "$samples"
  sleep "$interval"
done

check_end="$(docker_stats_check)"

{
  echo "spaceflux memory summary"
  echo "samples file: $(basename "$samples")"
  echo "window: duration ${duration}s, interval ${interval}s, ended $(date -u +%FT%TZ)"
  echo "source: cgroup v2 files; usage = memory.current - inactive_file (the docker stats figure)"
  echo
  echo "host"
  echo "  kernel: $(uname -r)"
  echo "  docker: $(docker version --format '{{.Server.Version}}')"
  echo "  cgroup: $(docker info --format 'v{{.CgroupVersion}} driver {{.CgroupDriver}}')"
  echo "  memory: $(free -h | awk '$1 == "Mem:" { print "total " $2 }'), $(free -h | awk '$1 == "Swap:" { print "swap " $2 }')"
  echo
  echo "containers"
  for n in "${names[@]}"; do
    echo "  $n"
    echo "    image: $(docker inspect -f '{{.Config.Image}}' "$n") id $(docker inspect -f '{{.Image}}' "$n")"
    echo "    started: $(docker inspect -f '{{.State.StartedAt}}' "$n")"
    echo "    limits: mem_limit $(docker inspect -f '{{.HostConfig.Memory}}' "$n") (0 is none), pids_limit $(docker inspect -f '{{.HostConfig.PidsLimit}}' "$n")"
    env_lines="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$n" | grep -E '^(INGEST_FEEDS|SWPC_INTERVAL|CELESTRAK_INTERVAL|KAFKA_HEAP_OPTS|KAFKA_OPTS|JAVA_TOOL_OPTIONS)=' || true)"
    echo "    env: ${env_lines:-none of INGEST_FEEDS, SWPC_INTERVAL, CELESTRAK_INTERVAL, KAFKA_HEAP_OPTS, KAFKA_OPTS, JAVA_TOOL_OPTIONS set}" | paste -sd' '
    echo "    memory.peak since container start: $(awk '{ printf "%.1f MiB", $1 / 1048576 }' "${cg[$n]}/memory.peak" 2>/dev/null || echo unavailable) (includes page cache)"
  done
  echo
  echo "per series over the window (MiB; pids as counts)"
  echo "  usage is memory.current minus inactive_file; usage+swap adds memory.swap.current"
  echo "  (for _host_used, the swap column is host swap in use)"
  printf '  %-22s %4s %9s %9s %9s %9s %9s %9s %9s\n' series n min median max swap_max 'u+s_med' 'u+s_max' max_pids
  awk -F'\t' 'NR > 2 { print $2 "\t" $3 "\t" $8 "\t" $9 }' "$samples" | awk -F'\t' '
    function med(a, n) { return (n % 2) ? a[(n + 1) / 2] : (a[n / 2] + a[n / 2 + 1]) / 2 }
    function isort(a, n,   i, j, t) { for (i = 2; i <= n; i++) { t = a[i]; for (j = i - 1; j > 0 && a[j] > t; j--) a[j + 1] = a[j]; a[j + 1] = t } }
    {
      k = $1
      if (!(k in cnt)) order[++nk] = k
      c = ++cnt[k]
      u[k, c] = $2 + 0
      w[k, c] = $2 + $3
      if ($3 != "" && $3 + 0 > smax[k] + 0) smax[k] = $3
      if (!(k in smax)) smax[k] = ($3 == "" ? 0 : $3)
      if ($4 != "" && $4 + 0 > pmax[k] + 0) pmax[k] = $4
    }
    END {
      for (i = 1; i <= nk; i++) {
        k = order[i]; n = cnt[k]
        for (c = 1; c <= n; c++) { a[c] = u[k, c]; b[c] = w[k, c] }
        isort(a, n); isort(b, n)
        printf "  %-22s %4d %9.1f %9.1f %9.1f %9.1f %9.1f %9.1f %9s\n", k, n, a[1] / 1048576, med(a, n) / 1048576, a[n] / 1048576, smax[k] / 1048576, med(b, n) / 1048576, b[n] / 1048576, (k in pmax ? pmax[k] : "-")
      }
    }' | sort
  echo
  echo "network bytes received per container over the window (poll evidence)"
  awk -F'\t' 'NR > 2 && $10 != "" { if (!($2 in first)) first[$2] = $10; last[$2] = $10 }
    END { for (k in first) printf "  %s rx delta %d bytes\n", k, last[k] - first[k] }' "$samples" | sort
  echo "  intervals where a container received more than 10 KiB:"
  awk -F'\t' 'NR > 2 && $10 != "" { if (($2 in prev) && $10 - prev[$2] > 10240) printf "    %s %s +%d bytes\n", strftime("%H:%M:%SZ", $1, 1), $2, $10 - prev[$2]; prev[$2] = $10 }' "$samples"
  echo
  echo "$check_start"
  echo "$check_end"
} | tee "$summary"
