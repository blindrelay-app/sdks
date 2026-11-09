#!/usr/bin/env bash
# Runs each SDK's conformance tests against one local mock server.
#
#   run-all.sh                 every language
#   run-all.sh go rust         only these
#
# A language runs on the local toolchain when that is new enough, otherwise in its official image
# through Podman when Podman is up, otherwise it is reported as skipped. Skips do not fail the run.
set -uo pipefail

sdks_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_dir="$(dirname "$sdks_dir")"
scratch_dir="${BLINDRELAY_SDK_TEST_DIR:-$sdks_dir/.test-output}"
log_dir="$scratch_dir/logs"
cache_dir="$scratch_dir/cache"
mkdir -p "$log_dir" "$cache_dir"

all_languages=(node python go php ruby java dotnet rust)
languages=("$@")
[[ ${#languages[@]} -gt 0 ]] || languages=("${all_languages[@]}")

mock_start_attempts=100
mock_start_poll_seconds=0.1
container_host="host.containers.internal"
loopback_host="127.0.0.1"

image_for() {
  case "$1" in
    node) echo "docker.io/library/node:22-alpine" ;;
    python) echo "docker.io/library/python:3.13-alpine" ;;
    go) echo "docker.io/library/golang:1.25-alpine" ;;
    php) echo "docker.io/library/php:8.3-cli-alpine" ;;
    ruby) echo "docker.io/library/ruby:3.4-alpine" ;;
    java) echo "docker.io/library/maven:3.9-eclipse-temurin-21" ;;
    dotnet) echo "mcr.microsoft.com/dotnet/sdk:8.0" ;;
    rust) echo "docker.io/library/rust:1-bookworm" ;;
  esac
}

native_ready() {
  case "$1" in
    node) command -v npm >/dev/null && node -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>20||(a===20&&b>=19)?0:1)' 2>/dev/null ;;
    python) python3 -c 'import sys;sys.exit(0 if sys.version_info>=(3,9) else 1)' 2>/dev/null ;;
    go) command -v go >/dev/null ;;
    php) php -r 'exit(PHP_VERSION_ID>=80100 && extension_loaded("curl") ? 0 : 1);' 2>/dev/null ;;
    ruby) ruby -e 'exit(Gem::Version.new(RUBY_VERSION)>=Gem::Version.new("3.0") ? 0 : 1)' 2>/dev/null ;;
    java) command -v mvn >/dev/null && [[ "$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)" -ge 17 ]] 2>/dev/null ;;
    dotnet) dotnet --list-runtimes 2>/dev/null | grep -q '^Microsoft.NETCore.App 8\.' ;;
    rust) command -v cargo >/dev/null ;;
  esac
}

test_command() {
  case "$1" in
    node) echo 'npm ci --no-audit --no-fund && npm test' ;;
    python) echo 'python3 -m unittest discover -s tests' ;;
    go) echo 'go vet ./... && go test ./...' ;;
    php) echo 'php tests/conformance.php' ;;
    ruby) echo 'ruby test/conformance.rb' ;;
    java) echo "mvn -q -B -Dmaven.repo.local=\"\$MAVEN_CACHE\" test" ;;
    dotnet) echo 'dotnet run --project Blindrelay.Conformance' ;;
    rust) echo 'cargo test --quiet' ;;
  esac
}

podman_ready() {
  command -v podman >/dev/null && podman info >/dev/null 2>&1
}

mock_output="$(mktemp)"
python3 "$sdks_dir/conformance/mock_server.py" >"$mock_output" 2>&1 &
mock_pid=$!
trap 'kill "$mock_pid" 2>/dev/null; rm -f "$mock_output"' EXIT

for ((attempt = 0; attempt < mock_start_attempts; attempt++)); do
  grep -q listening "$mock_output" && break
  sleep "$mock_start_poll_seconds"
done
mock_url="$(sed -n 's/^listening on //p' "$mock_output")"
if [[ -z "$mock_url" ]]; then
  echo "The mock server did not start:" >&2
  cat "$mock_output" >&2
  exit 1
fi

run_native() {
  local language="$1" log="$2"
  (cd "$sdks_dir/$language" &&
    BLINDRELAY_CONFORMANCE_URL="$mock_url" MAVEN_CACHE="$cache_dir/maven" \
      sh -c "$(test_command "$language")") >"$log" 2>&1
}

run_container() {
  local language="$1" log="$2"
  podman run --rm \
    -v "$sdks_dir:/sdks" \
    -v "$cache_dir:/cache" \
    -w "/sdks/$language" \
    -e "BLINDRELAY_CONFORMANCE_URL=${mock_url/$loopback_host/$container_host}" \
    -e MAVEN_CACHE=/cache/maven \
    -e CARGO_HOME=/cache/cargo \
    -e NUGET_PACKAGES=/cache/nuget \
    -e GOCACHE=/cache/go \
    -e npm_config_cache=/cache/npm \
    -e HOME=/tmp \
    -e DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    -e DOTNET_NOLOGO=1 \
    "$(image_for "$language")" sh -c "$(test_command "$language")" >"$log" 2>&1
}

python3 "$sdks_dir/conformance/check_error_codes.py" || exit 1

summary=()
failed=0
for language in "${languages[@]}"; do
  log="$log_dir/$language.log"
  if native_ready "$language"; then
    where="local"
    run_native "$language" "$log"
  elif podman_ready; then
    where="podman: $(image_for "$language")"
    run_container "$language" "$log"
  else
    summary+=("SKIP  $language (no local toolchain and Podman is not running; try: podman machine start)")
    continue
  fi
  if [[ $? -eq 0 ]]; then
    summary+=("PASS  $language ($where)")
  else
    failed=1
    summary+=("FAIL  $language ($where), log: $log")
    tail -n 30 "$log"
  fi
done

printf '%s\n' "${summary[@]}"
exit "$failed"
