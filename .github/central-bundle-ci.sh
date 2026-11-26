#!/usr/bin/env bash
# CI の release-bundle の job：使い捨ての鍵で bundle を組み、192 ファイルの形と署名を確かめる。秘密の値は使わない。
# あわせて、publish.yml と同じ `+print version` の照合を、書き換えた版で確かめる（sbt の出力の形が変わっていないこと）。
# 作業用の `version` を 0.0.0 に書き換えるので、CI の checkout の中だけで動かす（終わったら元に戻す）。
set -euo pipefail

if [[ "${CI:-}" != true ]]; then
  echo "::error::central-bundle-ci.sh rewrites the version file; run it only in CI" >&2
  exit 1
fi
here="$(dirname "$0")"
work="$(mktemp -d "${RUNNER_TEMP:-/tmp}/central-bundle-ci.XXXXXX")"
cp version "${work}/version.orig"
trap 'cp "${work}/version.orig" version; GNUPGHOME="${work}/gnupg" gpgconf --kill gpg-agent 2> /dev/null || true; rm -rf "$work"' EXIT

export GNUPGHOME="${work}/gnupg"
mkdir -m 700 "$GNUPGHOME"
passphrase="ci-throwaway"
gpg --batch --pinentry-mode loopback --passphrase "$passphrase" \
  --quick-gen-key 'CI throwaway key <ci@example.invalid>' rsa3072 sign 1d
PGP_SECRET="$(gpg --batch --pinentry-mode loopback --passphrase "$passphrase" --armor --export-secret-keys | base64 | tr -d '\n')"
PGP_PASSPHRASE="$passphrase"
export PGP_SECRET PGP_PASSPHRASE
public="${work}/public.asc"
gpg --batch --armor --export > "$public"

printf '0.0.0\n' > version
sbt -batch --error '+print version' | python3 "${here}/central-portal.py" check-version --tag v0.0.0
# central-bundle.sh は自分の GNUPGHOME に鍵を読み込む（publish.yml と同じ）
bash "${here}/central-bundle.sh" 0.0.0 "${work}/bundle.zip"
python3 "${here}/central-bundle.py" verify --zip "${work}/bundle.zip" --version 0.0.0

mkdir "${work}/unzipped" "${work}/verify-gnupg"
chmod 700 "${work}/verify-gnupg"
GNUPGHOME="${work}/verify-gnupg" gpg --batch --import "$public"
python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "${work}/bundle.zip" "${work}/unzipped"
signatures=0
while IFS= read -r -d '' signature; do
  GNUPGHOME="${work}/verify-gnupg" gpg --batch --verify "$signature" "${signature%.asc}" 2> /dev/null
  signatures=$((signatures + 1))
done < <(find "${work}/unzipped" -name '*.asc' -print0)
if [[ "$signatures" -ne 48 ]]; then
  echo "::error::expected 48 signatures, found ${signatures}" >&2
  exit 1
fi
echo "all ${signatures} signatures verify"
