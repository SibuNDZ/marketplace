# generate-baseline.ps1 — produce V1__baseline.sql from the REAL PostgreSQL schema.
#
# Strategy: let Hibernate create the schema ONCE against a fresh PostgreSQL instance,
# then pg_dump it. After this script runs, Hibernate never writes DDL again (validate).
#
# Run from the marketplace-api directory.
# Requires: docker compose file alongside, Maven wrapper, Docker Desktop running.

$ErrorActionPreference = "Stop"
$migrationDir = "src\main\resources\db\migration"

Write-Host "1) Fresh database (wiping dev volume)..."
docker compose down -v 2>$null
docker compose up -d
Write-Host "   Waiting for Postgres to be ready..."
do {
    Start-Sleep -Seconds 1
    $ready = docker compose exec postgres pg_isready -U marketplace -d marketplace 2>&1
} while ($ready -notmatch "accepting connections")
Write-Host "   Postgres ready."

Write-Host "2) Boot app ONCE with ddl-auto=create and Flyway disabled to build the schema..."
Write-Host "   -> Watch for 'Started MarketplaceApiApplication', then Ctrl+C to stop."
Write-Host "   (One-off overrides beat editing config files and forgetting to revert.)"
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" `
  "-Dspring-boot.run.jvmArguments=-Dspring.jpa.hibernate.ddl-auto=create -Dspring.flyway.enabled=false"

Write-Host "3) Dumping schema (no owners, no privileges, portable across environments)..."
New-Item -ItemType Directory -Force -Path $migrationDir | Out-Null
docker compose exec postgres pg_dump `
    -U marketplace -d marketplace `
    --schema-only --no-owner --no-privileges |
  Set-Content -Encoding utf8 "$migrationDir\V1__baseline.sql"

Write-Host ""
Write-Host "4) MANUAL CLEANUP of V1__baseline.sql before committing:"
Write-Host "   - Delete the SET statements block at the top (search_path, etc.)"
Write-Host "   - Delete any \connect or comment-on-database lines"
Write-Host "   - Verify sequences and identity columns look sane"
Write-Host "   - Do NOT edit table definitions — the dump IS the truth"
Write-Host ""
Write-Host "5) After cleanup, wipe and verify the round-trip:"
Write-Host "   docker compose down -v; docker compose up -d"
Write-Host "   .\mvnw spring-boot:run `"-Dspring-boot.run.profiles=dev`""
Write-Host "   -> Flyway should log 'Successfully applied 1 migration'"
Write-Host "   -> Hibernate validate should pass with no errors"
Write-Host "   -> Second boot: 'Schema is up to date. No migration necessary.'"
