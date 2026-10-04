# shellcheck shell=bash
# Temporary MySQL / Redis / backend helpers for upgrade acceptance runs (docs/phases/P0.md §6.3, §7.2).
#
#   P0_MYSQL_MODE=docker|local   docker: containers ftsm-p0-mysql / ftsm-p0-redis (label ftsm.phase=p0)
#                                local:  private mysqld/redis-server under $P0_TMP (no Docker needed)
#   P0_TMP=$(mktemp -d /tmp/ftsm-p0.XXXXXX)
#   DB_PASSWORD=root             only ever used for the temporary instance
#
# Host addresses: MySQL 127.0.0.1:3307, Redis 127.0.0.1:6380. Every command is built
# as a bash array; nothing is assembled into a string and re-evaluated.
# These functions never touch the system MySQL service, /var/lib/mysql, the system
# root account, or any container that lacks the ftsm.phase=p0 label.

P0_LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../lib/wait.sh
source "$P0_LIB_DIR/../lib/wait.sh"

: "${DB_PASSWORD:=root}"
P0_MYSQL_PORT=3307
P0_REDIS_PORT=6380
P0_MYSQL_IMAGE=mysql:8.0.46
P0_REDIS_IMAGE=redis:7.4.6-alpine
P0_MYSQL_CONTAINER=ftsm-p0-mysql
P0_REDIS_CONTAINER=ftsm-p0-redis
P0_LABEL=ftsm.phase=p0

_p0_require_tmp() {
  [[ -n ${P0_TMP:-} && -d $P0_TMP ]] || { echo "lib.sh: P0_TMP must point to an existing directory (mktemp -d)" >&2; return 2; }
}

_p0_mode() {
  case ${P0_MYSQL_MODE:-} in
    docker | local) echo "$P0_MYSQL_MODE" ;;
    *) echo "lib.sh: P0_MYSQL_MODE must be docker or local (got '${P0_MYSQL_MODE:-}')" >&2; return 2 ;;
  esac
}

mysql_cli() {
  if [[ ${P0_MYSQL_MODE:-} == docker ]]; then
    docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$P0_MYSQL_CONTAINER" mysql -uroot "$@"
  else
    MYSQL_PWD="$DB_PASSWORD" mysql --no-defaults --protocol=TCP -h127.0.0.1 -P"$P0_MYSQL_PORT" -uroot "$@"
  fi
}

mysqldump_cli() {
  if [[ ${P0_MYSQL_MODE:-} == docker ]]; then
    docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$P0_MYSQL_CONTAINER" mysqldump -uroot "$@"
  else
    MYSQL_PWD="$DB_PASSWORD" mysqldump --no-defaults --protocol=TCP -h127.0.0.1 -P"$P0_MYSQL_PORT" -uroot "$@"
  fi
}

redis_cli() {
  if [[ ${P0_MYSQL_MODE:-} == docker ]]; then
    docker exec -i "$P0_REDIS_CONTAINER" redis-cli "$@"
  else
    redis-cli -h 127.0.0.1 -p "$P0_REDIS_PORT" "$@"
  fi
}

# Target-addressed access used by loadtest/run.sh and drain.sh.
# P0 implements only `temp:<db>` (MySQL) and `temp` (Redis); P2 adds compose:, P6b adds k8s:.
# Unknown targets return 2.
mysql_target_cli() {
  local target=$1; shift
  case $target in
    temp:?*) mysql_cli "${target#temp:}" "$@" ;;
    *) echo "lib.sh: unsupported mysql target '$target'" >&2; return 2 ;;
  esac
}

redis_target_cli() {
  local target=$1; shift
  case $target in
    temp) redis_cli "$@" ;;
    *) echo "lib.sh: unsupported redis target '$target'" >&2; return 2 ;;
  esac
}

# docker mode: reuse a same-named container only if it carries our label and pinned image.
_p0_docker_container() {
  local name=$1 image=$2; shift 2
  local info
  if info=$(docker inspect -f '{{index .Config.Labels "ftsm.phase"}}|{{.Config.Image}}|{{.State.Running}}' "$name" 2>/dev/null); then
    if [[ $info == "p0|$image|"* ]]; then
      [[ $info == *"|true" ]] || docker start "$name" >/dev/null
      return 0
    fi
    echo "lib.sh: container $name exists but is not ours (label/image: $info); refusing to touch it" >&2
    return 5
  fi
  local -a args=(run -d --name "$name" --label "$P0_LABEL" "$@" "$image")
  docker "${args[@]}" >/dev/null
}

_p0_install_mysql_local() {
  command -v mysqld >/dev/null && return 0
  if [[ ${ALLOW_APT_INSTALL:-} != 1 ]]; then
    echo "lib.sh: mysqld not found. Install MySQL 8.0, or set ALLOW_APT_INSTALL=1 (disposable cloud containers only)" >&2
    return 6
  fi
  local -a sudo=()
  (( EUID == 0 )) || sudo=(sudo)
  # mysql-server-core-8.0 ships the same mysqld binary as mysql-server-8.0 but no
  # system service or /var/lib/mysql initialisation, so nothing system-wide starts.
  # Refresh the index first: superseded Ubuntu revisions are removed from the mirror.
  "${sudo[@]}" apt-get update -qq >&2 || return
  "${sudo[@]}" apt-get install -y --no-install-recommends mysql-server-core-8.0 mysql-client-8.0 >&2
}

start_temp_mysql() {
  local mode; mode=$(_p0_mode) || return
  _p0_require_tmp || return
  local evidence="$P0_LIB_DIR/evidence/p0"
  mkdir -p "$evidence"
  if [[ $mode == docker ]]; then
    _p0_docker_container "$P0_MYSQL_CONTAINER" "$P0_MYSQL_IMAGE" \
      -p "127.0.0.1:$P0_MYSQL_PORT:3306" -e "MYSQL_ROOT_PASSWORD=$DB_PASSWORD" || return
    # The image's init-time server has networking disabled, so a TCP ping only
    # succeeds once the real server is up.
    wait_cmd 180 "temp mysql container" -- \
      docker exec -e MYSQL_PWD="$DB_PASSWORD" "$P0_MYSQL_CONTAINER" mysqladmin -uroot --protocol=TCP -h127.0.0.1 ping || return
  else
    _p0_install_mysql_local || return
    local version
    version=$(mysqld --version)
    if ! [[ $version =~ Ver\ 8\.0\.[0-9]+ ]]; then
      echo "lib.sh: mysqld is not 8.0.x: $version" >&2
      return 6
    fi
    local datadir="$P0_TMP/mysql" sock="$P0_TMP/mysql.sock" pidf="$P0_TMP/mysql.pid" user
    local users_ok="$P0_TMP/mysql.users-ok"
    user=$(id -un)
    if [[ -f $pidf ]] && kill -0 "$(cat "$pidf")" 2>/dev/null; then
      : # already running from this P0_TMP
    else
      if [[ ! -d $datadir ]]; then
        mysqld --no-defaults --initialize-insecure --datadir="$datadir" --user="$user" \
          > "$P0_TMP/mysqld-init.log" 2>&1 || { tail -n 40 "$P0_TMP/mysqld-init.log" >&2; return 1; }
      fi
      # The packaged mysqld defaults secure_file_priv to /var/lib/mysql-files; keep it inside P0_TMP.
      mkdir -p "$P0_TMP/mysql-files"
      local -a args=(--no-defaults --datadir="$datadir" --port="$P0_MYSQL_PORT" --bind-address=127.0.0.1
        --mysqlx=OFF --socket="$sock" --pid-file="$pidf" --user="$user"
        --secure-file-priv="$P0_TMP/mysql-files")
      mysqld "${args[@]}" > "$P0_TMP/mysqld.log" 2>&1 &
    fi
    if [[ ! -f $users_ok ]]; then
      # Fresh --initialize-insecure datadir: root@localhost has no password yet.
      wait_cmd 60 "temp mysqld" -- mysqladmin --no-defaults --socket="$sock" -uroot ping \
        || { tail -n 40 "$P0_TMP/mysqld.log" >&2; return 1; }
      mysql --no-defaults --socket="$sock" -uroot -e \
        "CREATE USER 'root'@'127.0.0.1' IDENTIFIED BY '$DB_PASSWORD'; GRANT ALL ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION; ALTER USER 'root'@'localhost' IDENTIFIED BY '$DB_PASSWORD';" \
        || return 1
      touch "$users_ok"
    fi
    wait_cmd 60 "temp mysqld (tcp)" -- env MYSQL_PWD="$DB_PASSWORD" \
      mysqladmin --no-defaults --protocol=TCP -h127.0.0.1 -P"$P0_MYSQL_PORT" -uroot ping \
      || { tail -n 40 "$P0_TMP/mysqld.log" >&2; return 1; }
  fi
  mysql_cli --batch --skip-column-names -e 'SELECT VERSION()' > "$evidence/mysql-version.txt" || return
  echo "temp mysql ($mode) ready: $(cat "$evidence/mysql-version.txt")" >&2
}

stop_temp_mysql() {
  local mode; mode=$(_p0_mode) || return
  if [[ $mode == docker ]]; then
    local id
    id=$(docker ps -aq --filter "label=$P0_LABEL" --filter "name=^${P0_MYSQL_CONTAINER}$")
    [[ -z $id ]] || docker rm -f "$id" >/dev/null
  else
    _p0_require_tmp || return
    local pidf="$P0_TMP/mysql.pid" pid
    [[ -f $pidf ]] || return 0
    pid=$(cat "$pidf")
    MYSQL_PWD="$DB_PASSWORD" timeout 30 mysqladmin --no-defaults --socket="$P0_TMP/mysql.sock" -uroot shutdown 2>/dev/null || true
    wait_cmd 60 "temp mysqld exit" -- sh -c '! kill -0 "$1" 2>/dev/null' _ "$pid"
  fi
}

start_temp_redis() {
  local mode; mode=$(_p0_mode) || return
  if [[ $mode == docker ]]; then
    _p0_docker_container "$P0_REDIS_CONTAINER" "$P0_REDIS_IMAGE" -p "127.0.0.1:$P0_REDIS_PORT:6379" || return
  else
    _p0_require_tmp || return
    command -v redis-server >/dev/null || { echo "lib.sh: redis-server not found" >&2; return 6; }
    local pidf="$P0_TMP/redis.pid"
    if ! { [[ -f $pidf ]] && kill -0 "$(cat "$pidf")" 2>/dev/null; }; then
      local -a args=(--port "$P0_REDIS_PORT" --bind 127.0.0.1 --dir "$P0_TMP" --daemonize yes
        --pidfile "$pidf" --logfile "$P0_TMP/redis.log")
      redis-server "${args[@]}" || return
    fi
  fi
  local -a ping=(redis-cli -h 127.0.0.1 -p "$P0_REDIS_PORT" ping)
  [[ $mode == docker ]] && ping=(docker exec "$P0_REDIS_CONTAINER" redis-cli ping)
  wait_cmd 30 "temp redis" -- "${ping[@]}" || return
  echo "temp redis ($mode) ready" >&2
}

stop_temp_redis() {
  local mode; mode=$(_p0_mode) || return
  if [[ $mode == docker ]]; then
    local id
    id=$(docker ps -aq --filter "label=$P0_LABEL" --filter "name=^${P0_REDIS_CONTAINER}$")
    [[ -z $id ]] || docker rm -f "$id" >/dev/null
  else
    _p0_require_tmp || return
    local pidf="$P0_TMP/redis.pid" pid
    [[ -f $pidf ]] || return 0
    pid=$(cat "$pidf")
    kill "$pid" 2>/dev/null || true
    wait_cmd 30 "temp redis exit" -- sh -c '! kill -0 "$1" 2>/dev/null' _ "$pid"
  fi
}

# start_backend JAR DB PORT LOG   (sets BACKEND_PID; returns wait_http's code)
start_backend() {
  local jar=$1 db=$2 port=$3 log=$4
  env DB_URL="jdbc:mysql://127.0.0.1:3307/$db?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
      DB_USERNAME=root DB_PASSWORD="$DB_PASSWORD" REDIS_HOST=127.0.0.1 REDIS_PORT=6380 \
      JWT_SECRET="$JWT_SECRET" SERVER_PORT="$port" MAIL_ENABLED=false LLM_API_KEY= \
      java -jar "$jar" > "$log" 2>&1 &
  BACKEND_PID=$!
  wait_http "http://127.0.0.1:$port/actuator/health" 180 "$BACKEND_PID" "$log"
}
stop_backend() { [[ -n ${BACKEND_PID:-} ]] && kill "$BACKEND_PID" 2>/dev/null; wait "$BACKEND_PID" 2>/dev/null || true; BACKEND_PID=; }
