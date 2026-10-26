#!/bin/bash
set -euo pipefail

# Only run in Claude Code cloud (remote) sessions
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

# Python dependencies (idempotent; pip skips what is already satisfied)
python3 -m pip install --quiet -r requirements.txt
python3 -m pip install --quiet pytest ruff

# graphify skill for Claude Code
python3 -m pip install --quiet graphifyy
graphify install --platform claude >/dev/null
