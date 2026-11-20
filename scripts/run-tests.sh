#!/usr/bin/env bash
# Runs the local Groovy driver tests (tests/**/*Test.groovy) against the
# harness in tests/harness. Needs groovy on PATH (brew install groovy).
# Exits non-zero if any test fails. See STYLE.md, "Testing".
set -euo pipefail
cd "$(dirname "$0")/.."
command -v groovy >/dev/null || { echo "groovy not found on PATH (brew install groovy)" >&2; exit 2; }
status=0
while IFS= read -r t; do
    echo "== ${t}"
    groovy -cp tests "${t}" || status=1
done < <(find tests -name '*Test.groovy' | sort)
exit "${status}"
