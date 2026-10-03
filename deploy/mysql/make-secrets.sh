#!/usr/bin/env bash
# Writes one random password per MySQL account into deploy/secrets/, for the Compose stack.
# Existing files are kept, so running it again never changes a password the data volume already uses.
# Compose mounts file secrets as they are, and the MySQL and migrate containers read them as their own
# non root users, so the files are world readable inside a directory only this user can enter.
set -euo pipefail
set +x
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/secrets"
mkdir -p "$dir"
chmod 700 "$dir"
for name in mysql_root_password mysql_migrate_password mysql_consumer_password mysql_api_password; do
	file="$dir/$name"
	if [ -e "$file" ]; then
		echo "kept $name"
		continue
	fi
	password=$(head -c 4096 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')
	if [ "${#password}" -lt 40 ]; then
		echo "not enough random characters for $name" >&2
		exit 1
	fi
	(umask 022 && printf '%s' "${password:0:40}" >"$file")
	unset password
	echo "wrote $name"
done
