#!/usr/bin/env bash
set -euo pipefail

# Pin the existing TensorFlow Task Audio YAMNet model. This is the SHA-256 of
# the artifact downloaded from the TensorFlow URL below on 2026-10-06.
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
model_dir="$project_dir/app/src/main/assets"
model_path="$model_dir/yamnet.tflite"
model_sha256="10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de"
model_url="https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite"

verify_model() {
    python3 - "$1" "$model_sha256" <<'PY'
import hashlib, pathlib, sys
path = pathlib.Path(sys.argv[1])
if not path.is_file():
    raise SystemExit(1)
data = path.read_bytes()
if len(data) < 1_000_000 or data[4:8] != b'TFL3':
    raise SystemExit(1)
if hashlib.sha256(data).hexdigest() != sys.argv[2]:
    raise SystemExit(1)
PY
}

if verify_model "$model_path"; then
    echo "YAMNet model verified."
    exit 0
fi

mkdir -p "$model_dir"
temporary_path="$(mktemp "$model_dir/yamnet.XXXXXX")"
trap 'rm -f "$temporary_path"' EXIT
curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 \
    "$model_url" --output "$temporary_path"
if ! verify_model "$temporary_path"; then
    echo "YAMNet download failed integrity verification; existing model preserved." >&2
    exit 1
fi
mv "$temporary_path" "$model_path"
echo "YAMNet model downloaded and verified."
