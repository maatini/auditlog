#!/bin/bash
set -e
cd "$(dirname "$0")"
REPO="$PWD/.m2-repo"

# PostgreSQL-Container starten (falls nicht bereits)
PG_CONTAINER="audit-demo-pg"
if ! docker ps --format "{{.Names}}" | grep -q "^${PG_CONTAINER}$"; then
  echo ">> Starte PostgreSQL..."
  docker run --rm -d --name "$PG_CONTAINER" \
    -e POSTGRES_USER=demo -e POSTGRES_PASSWORD=demo -e POSTGRES_DB=audit_demo \
    -p 5439:5432 postgres:16-alpine
  sleep 3
fi

echo ">> Baue Core-Library..."
mvn install -DskipTests -Djacoco.skip=true -Dmaven.repo.local="$REPO"

echo ">> Starte Demo..."
mvn -f example/pom.xml compile exec:java -Dexec.mainClass=io.audit.demo.AuditLogDemo -Dmaven.repo.local="$REPO"

echo ">> Fertig!"
