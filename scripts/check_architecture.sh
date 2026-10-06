#!/usr/bin/env bash
set -euo pipefail

# ReveriePaint Architecture Guardrail Check Script
# Enforces architectural rules from AGENTS.md

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ERRORS=0

echo "[1/3] Checking unidirectional dependencies (ui -> core -> model)..."

# Rule: model must not depend on core or ui
if grep -rnE "import com\.reverie\.paint\.(core|ui)" app/src/main/java/com/reverie/paint/model/ 2>/dev/null; then
    echo "ERROR: Architecture violation: 'model' layer cannot import 'core' or 'ui'"
    ERRORS=$((ERRORS + 1))
fi

# Rule: core must not depend on ui
if grep -rnE "import com\.reverie\.paint\.ui" app/src/main/java/com/reverie/paint/core/ 2>/dev/null; then
    echo "ERROR: Architecture violation: 'core' layer cannot import 'ui'"
    ERRORS=$((ERRORS + 1))
fi

echo "[2/3] Checking JNI boundary encapsulation..."

# Rule: JNI native methods can only be declared in ReverieCoreBridge.kt
ILLEGAL_NATIVE=$(grep -rn "external fun " app/src/main/java/ 2>/dev/null | grep -v "ReverieCoreBridge.kt" || true)
if [ -n "$ILLEGAL_NATIVE" ]; then
    echo "ERROR: JNI boundary violation: 'external fun' found outside ReverieCoreBridge.kt:"
    echo "$ILLEGAL_NATIVE"
    ERRORS=$((ERRORS + 1))
fi

echo "[3/3] Checking C++ SPDX license headers..."

# Rule: C++ files must contain SPDX GPL header
for f in $(find app/src/main/cpp -type f \( -name "*.cpp" -o -name "*.h" \)); do
    if ! grep -qE "SPDX-License-Identifier: GPL-(2\.0|3\.0)-or-later" "$f"; then
        echo "ERROR: Missing SPDX GPL header in C++ file: $f"
        ERRORS=$((ERRORS + 1))
    fi
done

if [ "$ERRORS" -gt 0 ]; then
    echo "Architecture check failed with $ERRORS error(s)"
    exit 1
fi

echo "All architecture guardrail checks passed successfully"
exit 0
