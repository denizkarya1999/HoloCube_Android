#!/usr/bin/env bash
# Creates an isolated Python 3.8 for Chaquopy's pip step (the embedded Python version).
# Nothing is installed into the system Python. Re-run after cloning on a new computer.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
if [[ ! -x .build-tools/tooling/bin/uv ]]; then
    python3 -m pip install --target .build-tools/tooling 'uv==0.12.23'
fi
export UV_PYTHON_INSTALL_DIR="$project_dir/.build-tools/python"
export UV_PYTHON_BIN_DIR="$project_dir/.build-tools/bin"
export UV_CACHE_DIR="$project_dir/.build-tools/uv-cache"
uv="$project_dir/.build-tools/tooling/bin/uv"
"$uv" python install 3.8
if [[ ! -x .build-tools/pt/bin/python ]]; then
    "$uv" venv --python 3.8 .build-tools/pt
fi
.build-tools/pt/bin/python --version
echo 'Build Python is ready: .build-tools/pt/bin/python (found automatically by app/build.gradle.kts).'
