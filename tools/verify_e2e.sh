#!/usr/bin/env bash
# Compatibility entry point; the portable dispatcher owns suite/scenario mapping.
exec python3 -B "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify_e2e.py" "$@"
