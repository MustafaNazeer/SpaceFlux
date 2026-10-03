# Creates the three SpaceFlux accounts on the first start of an empty data directory.
# The image's entrypoint sources this file, which provides docker_process_sql; passwords
# reach the server on standard input only, never on a command line or in a log line.
set +x

sf_check_name() {
	if [[ ! "$2" =~ ^[a-z][a-z0-9_]{0,31}$ ]]; then
		echo "10-users.sh: $1 must match ^[a-z][a-z0-9_]{0,31}\$" >&2
		exit 1
	fi
}

sf_read_password() {
	local file="/run/secrets/$1"
	if [ ! -r "$file" ]; then
		echo "10-users.sh: secret $1 is missing" >&2
		exit 1
	fi
	sf_password=$(<"$file")
	if [[ ! "$sf_password" =~ ^[A-Za-z0-9]{32,}$ ]]; then
		echo "10-users.sh: secret $1 must be at least 32 characters of [A-Za-z0-9]" >&2
		exit 1
	fi
}

sf_check_name MYSQL_MIGRATE_USER "$MYSQL_MIGRATE_USER"
sf_check_name MYSQL_CONSUMER_USER "$MYSQL_CONSUMER_USER"
sf_check_name MYSQL_API_USER "$MYSQL_API_USER"
sf_check_name MYSQL_DATABASE "$MYSQL_DATABASE"

sf_read_password mysql_migrate_password
sf_migrate_password=$sf_password
sf_read_password mysql_consumer_password
sf_consumer_password=$sf_password
sf_read_password mysql_api_password
sf_api_password=$sf_password
unset sf_password

# The consumer and API users get their table grants from the last migration, once the tables exist.
docker_process_sql --database=mysql <<-EOSQL
	CREATE USER '${MYSQL_MIGRATE_USER}'@'%' IDENTIFIED WITH caching_sha2_password BY '${sf_migrate_password}' REQUIRE SSL;
	CREATE USER '${MYSQL_CONSUMER_USER}'@'%' IDENTIFIED WITH caching_sha2_password BY '${sf_consumer_password}' REQUIRE SSL;
	CREATE USER '${MYSQL_API_USER}'@'%' IDENTIFIED WITH caching_sha2_password BY '${sf_api_password}' REQUIRE SSL;
	GRANT CREATE, ALTER, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_MIGRATE_USER}'@'%' WITH GRANT OPTION;
	GRANT CREATE, DELETE ON \`${MYSQL_DATABASE}\`.flyway_schema_history TO '${MYSQL_MIGRATE_USER}'@'%';
EOSQL

unset sf_migrate_password sf_consumer_password sf_api_password
