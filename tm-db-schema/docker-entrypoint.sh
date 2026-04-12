#!/bin/sh
# Translates env vars into Liquibase CLI arguments.
# Usage:  docker run --rm -e DB_HOST=... -e ... tm-db-schema:local [command]
# Default command: update
# Override: docker run ... tm-db-schema:local rollbackCount 1
set -eu

# Validate required variables are set and non-empty.
# An unset or empty BOOTSTRAP_ADMIN_BCRYPT_HASH would insert an empty password
# hash for the bootstrap admin, enabling authentication bypass.
: "${DB_HOST:?DB_HOST is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${BOOTSTRAP_ADMIN_BCRYPT_HASH:?BOOTSTRAP_ADMIN_BCRYPT_HASH is required — generate with: htpasswd -bnBC 12 '' yourpassword | tr -d ':\n'}"

# Backward compatibility: older env templates escaped bcrypt as $$2y$... .
# Liquibase must receive a canonical $2y$... hash for Spring's BCrypt matcher.
BOOTSTRAP_ADMIN_BCRYPT_HASH="$(printf '%s' "$BOOTSTRAP_ADMIN_BCRYPT_HASH" | sed 's/^\$\$/\$/')"

# Default to "update" when no command argument is supplied.
# "${@:-update}" is not portable across all POSIX sh implementations
# (Alpine uses busybox sh, not bash), so use an explicit guard instead.
if [ "$#" -eq 0 ]; then
  set -- update
fi

exec liquibase \
  --url="jdbc:postgresql://${DB_HOST}:${DB_PORT:-5432}/${DB_NAME}" \
  --username="${DB_USERNAME}" \
  --password="${DB_PASSWORD}" \
  --changelog-file="changelog/db.changelog-master.yaml" \
  --contexts="${LIQUIBASE_CONTEXTS:-prod}" \
  "$@" \
  "-DBOOTSTRAP_ADMIN_BCRYPT_HASH=${BOOTSTRAP_ADMIN_BCRYPT_HASH}"