#!/usr/bin/env bash
set -euo pipefail

COMPOSE_PROJECT="monkey-shop"
APP_SERVICE="myshop"
MYSQL_SERVICE="mysql"
MINIMUM_FLYWAY_VERSION="18"
REQUIRE_POPULATED_PII="false"

usage() {
  cat <<'USAGE'
Usage: scripts/verify-runtime-data-protection.sh [options]

Options:
  --compose-project NAME        Docker Compose project name (default: monkey-shop)
  --app-service NAME            Compose app service name (default: myshop)
  --mysql-service NAME          Compose MySQL service name (default: mysql)
  --minimum-flyway-version N    Minimum successful Flyway version (default: 18)
  --require-populated-pii       Require at least one populated protected PII value
  -h, --help                    Show this help

The verifier prints only aggregate counts and runtime flags; it does not print
secrets or raw PII values.
USAGE
}

fail() {
  echo "Runtime data protection gate failed: $*" >&2
  exit 1
}

assert_equals() {
  local actual="$1"
  local expected="$2"
  local message="$3"
  if [[ "$actual" != "$expected" ]]; then
    fail "$message"
  fi
}

assert_nonempty() {
  local value="$1"
  local message="$2"
  if [[ -z "$value" ]]; then
    fail "$message"
  fi
}

to_int() {
  local value="${1:-}"
  local name="$2"
  value="${value//$'\r'/}"
  if [[ -z "$value" || "$value" == "NULL" ]]; then
    echo 0
    return
  fi
  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    fail "$name must be an integer but was '$value'"
  fi
  echo "$value"
}

compose_exec() {
  docker compose -p "$COMPOSE_PROJECT" exec -T "$@"
}

get_app_flag() {
  local name="$1"
  compose_exec "$APP_SERVICE" printenv "$name" 2>/dev/null | tr -d '\r' || true
}

query_mysql() {
  local sql="$1"
  local encoded
  encoded="$(printf '%s' "$sql" | base64 | tr -d '\n')"
  compose_exec "$MYSQL_SERVICE" sh -c "printf '%s' '$encoded' | base64 -d | MYSQL_PWD=\"\$MYSQL_PASSWORD\" mysql -u\"\$MYSQL_USER\" -N -B \"\$MYSQL_DATABASE\""
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose-project)
      COMPOSE_PROJECT="${2:?missing value for --compose-project}"
      shift 2
      ;;
    --app-service)
      APP_SERVICE="${2:?missing value for --app-service}"
      shift 2
      ;;
    --mysql-service)
      MYSQL_SERVICE="${2:?missing value for --mysql-service}"
      shift 2
      ;;
    --minimum-flyway-version)
      MINIMUM_FLYWAY_VERSION="${2:?missing value for --minimum-flyway-version}"
      shift 2
      ;;
    --require-populated-pii)
      REQUIRE_POPULATED_PII="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

if [[ ! "$MINIMUM_FLYWAY_VERSION" =~ ^[0-9]+$ ]]; then
  fail "--minimum-flyway-version must be an integer"
fi

echo "==> Runtime PII configuration"
encryption_enabled="$(get_app_flag APP_PII_ENCRYPTION_ENABLED)"
allow_plaintext_read="$(get_app_flag APP_PII_ALLOW_PLAINTEXT_READ)"
backfill_enabled="$(get_app_flag APP_PII_BACKFILL_ENABLED)"
key_provider="$(get_app_flag APP_PII_KEY_PROVIDER)"
key_version="$(get_app_flag APP_PII_KEY_VERSION)"

assert_equals "$encryption_enabled" "true" "APP_PII_ENCRYPTION_ENABLED must be true"
assert_equals "$allow_plaintext_read" "false" "APP_PII_ALLOW_PLAINTEXT_READ must be false after backfill"
assert_equals "$backfill_enabled" "false" "APP_PII_BACKFILL_ENABLED must be false outside one-time migration"
assert_nonempty "$key_provider" "APP_PII_KEY_PROVIDER must be set"
assert_nonempty "$key_version" "APP_PII_KEY_VERSION must be set"

read -r -d '' SQL_TEXT <<'SQL' || true
SELECT 'flyway.version' AS metric,
       COUNT(*) AS total_rows,
       COALESCE(MAX(CASE WHEN `success` = 1 THEN CAST(`version` AS UNSIGNED) END), 0) AS populated,
       COALESCE(MAX(CASE WHEN `success` = 1 THEN CAST(`version` AS UNSIGNED) END), 0) >= __MINIMUM_FLYWAY_VERSION__ AS protected,
       COALESCE(MAX(CASE WHEN `success` = 1 THEN CAST(`version` AS UNSIGNED) END), 0) < __MINIMUM_FLYWAY_VERSION__ AS unprotected
FROM flyway_schema_history
UNION ALL
SELECT 'user.phone', COUNT(*), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> ''), 0), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> '' AND `phone` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> '' AND `phone` NOT LIKE 'enc:v1:%'), 0) FROM `user`
UNION ALL
SELECT 'user.email', COUNT(*), COALESCE(SUM(`email` IS NOT NULL AND `email` <> ''), 0), COALESCE(SUM(`email` IS NOT NULL AND `email` <> '' AND `email` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`email` IS NOT NULL AND `email` <> '' AND `email` NOT LIKE 'enc:v1:%'), 0) FROM `user`
UNION ALL
SELECT 'user.phone_hmac', COUNT(*), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> ''), 0), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> '' AND `phone_hmac` REGEXP '^[0-9a-f]{64}$'), 0), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> '' AND (`phone_hmac` IS NULL OR `phone_hmac` NOT REGEXP '^[0-9a-f]{64}$')), 0) FROM `user`
UNION ALL
SELECT 'address.receiver_name', COUNT(*), COALESCE(SUM(`receiver_name` IS NOT NULL AND `receiver_name` <> ''), 0), COALESCE(SUM(`receiver_name` IS NOT NULL AND `receiver_name` <> '' AND `receiver_name` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`receiver_name` IS NOT NULL AND `receiver_name` <> '' AND `receiver_name` NOT LIKE 'enc:v1:%'), 0) FROM `address`
UNION ALL
SELECT 'address.phone', COUNT(*), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> ''), 0), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> '' AND `phone` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> '' AND `phone` NOT LIKE 'enc:v1:%'), 0) FROM `address`
UNION ALL
SELECT 'address.phone_hmac', COUNT(*), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> ''), 0), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> '' AND `phone_hmac` REGEXP '^[0-9a-f]{64}$'), 0), COALESCE(SUM(`phone` IS NOT NULL AND `phone` <> '' AND (`phone_hmac` IS NULL OR `phone_hmac` NOT REGEXP '^[0-9a-f]{64}$')), 0) FROM `address`
UNION ALL
SELECT 'address.detail_address', COUNT(*), COALESCE(SUM(`detail_address` IS NOT NULL AND `detail_address` <> ''), 0), COALESCE(SUM(`detail_address` IS NOT NULL AND `detail_address` <> '' AND `detail_address` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`detail_address` IS NOT NULL AND `detail_address` <> '' AND `detail_address` NOT LIKE 'enc:v1:%'), 0) FROM `address`
UNION ALL
SELECT 'orders.buyer_name', COUNT(*), COALESCE(SUM(`buyer_name` IS NOT NULL AND `buyer_name` <> ''), 0), COALESCE(SUM(`buyer_name` IS NOT NULL AND `buyer_name` <> '' AND `buyer_name` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`buyer_name` IS NOT NULL AND `buyer_name` <> '' AND `buyer_name` NOT LIKE 'enc:v1:%'), 0) FROM `orders`
UNION ALL
SELECT 'orders.receiver_name', COUNT(*), COALESCE(SUM(`receiver_name` IS NOT NULL AND `receiver_name` <> ''), 0), COALESCE(SUM(`receiver_name` IS NOT NULL AND `receiver_name` <> '' AND `receiver_name` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`receiver_name` IS NOT NULL AND `receiver_name` <> '' AND `receiver_name` NOT LIKE 'enc:v1:%'), 0) FROM `orders`
UNION ALL
SELECT 'orders.receiver_phone', COUNT(*), COALESCE(SUM(`receiver_phone` IS NOT NULL AND `receiver_phone` <> ''), 0), COALESCE(SUM(`receiver_phone` IS NOT NULL AND `receiver_phone` <> '' AND `receiver_phone` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`receiver_phone` IS NOT NULL AND `receiver_phone` <> '' AND `receiver_phone` NOT LIKE 'enc:v1:%'), 0) FROM `orders`
UNION ALL
SELECT 'orders.receiver_phone_hmac', COUNT(*), COALESCE(SUM(`receiver_phone` IS NOT NULL AND `receiver_phone` <> ''), 0), COALESCE(SUM(`receiver_phone` IS NOT NULL AND `receiver_phone` <> '' AND `receiver_phone_hmac` REGEXP '^[0-9a-f]{64}$'), 0), COALESCE(SUM(`receiver_phone` IS NOT NULL AND `receiver_phone` <> '' AND (`receiver_phone_hmac` IS NULL OR `receiver_phone_hmac` NOT REGEXP '^[0-9a-f]{64}$')), 0) FROM `orders`
UNION ALL
SELECT 'orders.address_snapshot', COUNT(*), COALESCE(SUM(`address_snapshot` IS NOT NULL AND `address_snapshot` <> ''), 0), COALESCE(SUM(`address_snapshot` IS NOT NULL AND `address_snapshot` <> '' AND `address_snapshot` LIKE 'enc:v1:%'), 0), COALESCE(SUM(`address_snapshot` IS NOT NULL AND `address_snapshot` <> '' AND `address_snapshot` NOT LIKE 'enc:v1:%'), 0) FROM `orders`;
SQL

SQL_TEXT="${SQL_TEXT//__MINIMUM_FLYWAY_VERSION__/$MINIMUM_FLYWAY_VERSION}"

echo "==> Runtime PII database aggregate"
rows="$(query_mysql "$SQL_TEXT")"
populated_total=0
while IFS=$'\t' read -r metric _total_rows populated _protected unprotected extra; do
  [[ -z "${metric:-}" ]] && continue
  [[ -z "${extra:-}" ]] || fail "unexpected PII aggregate row for $metric"
  populated_count="$(to_int "$populated" "$metric populated")"
  unprotected_count="$(to_int "$unprotected" "$metric unprotected")"
  populated_total=$((populated_total + populated_count))
  if (( unprotected_count != 0 )); then
    fail "$metric has $unprotected_count unprotected values"
  fi
  printf '%s: populated=%s, unprotected=%s\n' "$metric" "$populated_count" "$unprotected_count"
done <<< "$rows"

if [[ "$REQUIRE_POPULATED_PII" == "true" && "$populated_total" -le 0 ]]; then
  fail "runtime database must contain at least one populated PII value"
fi

echo "Runtime data protection gate completed successfully for compose project $COMPOSE_PROJECT"
