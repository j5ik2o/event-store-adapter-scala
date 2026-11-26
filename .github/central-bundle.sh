#!/usr/bin/env bash
# Maven Central に上げる bundle を作る。publish.yml と CI の release-bundle の job が呼ぶ。
# 使い方: central-bundle.sh <版 X.Y.Z> <出力する zip>
# 署名の鍵は環境変数 PGP_SECRET（base64 にした秘密鍵）と PGP_PASSPHRASE（sbt-pgp が読む）。
# 鍵は一時の GNUPGHOME に読み込み、終わったら gpg-agent を止めて消す（パスフレーズを覚えた agent を残さない）。
set -euo pipefail

version="$1"
output="$2"
work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/central-bundle.XXXXXX")"
export GNUPGHOME="${work}/gnupg"
trap 'gpgconf --kill gpg-agent 2> /dev/null || true; rm -rf "$work"' EXIT
mkdir -m 700 "$GNUPGHOME"
printf '%s' "$PGP_SECRET" | base64 -d | gpg --batch --import
repository="${work}/repository"

sbt -batch "set every publishTo := Some(\"central-bundle\" at \"file://${repository}\")" +publishSigned
python3 "$(dirname "$0")/central-bundle.py" build --repository "$repository" --version "$version" --output "$output"
