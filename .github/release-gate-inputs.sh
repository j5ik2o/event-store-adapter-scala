#!/usr/bin/env bash
# 関門 (release-gate.py check) にかける事実を、指定のディレクトリに書く。
# event-store-adapter-js の .github/release-gate-inputs.sh を写し、版のファイルを `version` に替えたもの。
# 使い方: release-gate-inputs.sh <コミット> <出力先>
# 環境変数: GH_TOKEN、GITHUB_REPOSITORY、PULLS_RETRY_SECONDS（既定 10。PR の照会を再試行する間隔）、
#           REPO1_URL（テストで差し替える）
# 親（またはコミット）に `version` が無いときは、parent-version（または version）を書かない。関門はそれを「無い」と読む。
# 公開先に必要な版（release-gate.py の REQUIRED_PUBLISHED）があるときは、repo1 を読んで required-published に true か
# false を書く。読めなければ書かない。関門は、その版より新しい版をそのとき拒否する（照会が要らない判定では落とさない）。
set -euo pipefail

sha="$1"
out="$2"
mkdir -p "$out"

git fetch --no-tags origin main
on_main=false
if git merge-base --is-ancestor "$sha" FETCH_HEAD; then
  on_main=true
fi
echo "$on_main" > "${out}/on-main"
# 親の数。通常のマージ（親が2つ）は関門が拒否する
git rev-list --parents -n 1 "$sha" | awk '{print NF - 1}' > "${out}/parents"
if git cat-file -e "${sha}^:version" 2> /dev/null; then
  git cat-file blob "${sha}^:version" > "${out}/parent-version"
fi
if git cat-file -e "${sha}:version" 2> /dev/null; then
  git cat-file blob "${sha}:version" > "${out}/version"
fi
git diff --name-only "${sha}^" "$sha" > "${out}/changed-files"
git ls-remote --tags origin > "${out}/remote-tags"

here="$(dirname "$0")"
required="$(python3 "${here}/release-gate.py" required-version)"
if [[ -n "$required" ]]; then
  if python3 "${here}/central-portal.py" published --version "$required" > "${out}/required-published.tmp"; then
    mv "${out}/required-published.tmp" "${out}/required-published"
    echo "${required} on Maven Central: $(cat "${out}/required-published")"
  else
    rm -f "${out}/required-published.tmp"
    echo "could not read whether ${required} is on Maven Central; the gate refuses newer versions"
  fi
fi

# マージの直後は、コミットと PR の関連付けがまだ返らないことがある。一時的な失敗と空の応答だけを数回やり直す。
attempts=5
read_ok=false
for attempt in $(seq 1 "$attempts"); do
  if gh api "repos/${GITHUB_REPOSITORY}/commits/${sha}/pulls" > "${out}/pulls.json"; then
    read_ok=true
    if [[ "$(tr -d '[:space:]' < "${out}/pulls.json")" != "[]" ]]; then
      break
    fi
    echo "no pull request is associated with ${sha} yet (${attempt}/${attempts})"
  else
    read_ok=false
    echo "could not read the pull requests of ${sha} (${attempt}/${attempts})"
  fi
  if [[ "$attempt" -lt "$attempts" ]]; then
    sleep "${PULLS_RETRY_SECONDS:-10}"
  fi
done
if [[ "$read_ok" != true ]]; then
  echo "::error::could not read the pull requests of ${sha}; re-run the job"
  exit 1
fi
