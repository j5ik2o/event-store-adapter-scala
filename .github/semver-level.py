#! /usr/bin/env python3
# -*- coding: utf-8 -*-
"""Bump Version の level（major は除く）を、git log --format='%s%x1f%b%x1e' から決める。

R5 の前は mathieudutour/github-tag-action@v7.0.0 が決めていた（commit_analyzer_preset: conventionalcommits、
custom_release_rules: revert:minor、default_bump: patch）。その決め方を写す（設計 design-r5.md §3）。

- 件名が Conventional Commits の見出し（`^(\\w*)(?:\\((.*)\\))?!?: (.*)$`）に合い、型が feat か revert のものが
  1つでもあれば minor、ほかは patch（何も合わなくても default_bump の patch）。
- major（件名の `!`、本文の BREAKING CHANGE: / BREAKING-CHANGE:）と、明示の level は、bump-version.yml の
  changes の手順が先に決める。このスクリプトは、そのどちらも無いときだけ呼ばれる。
- 写さないもの：action の ignore_keywords（[no-release]・[skip-release]。この repository で使った例は無い）と、
  範囲の中で取り消されたコミットとその revert の両方を外す filter（feat と、それを取り消す revert だけの範囲では、
  action は patch、ここでは minor。まれな場合で、版が大きくなる側に倒れる）。
"""

import re
import sys

HEADER = re.compile(r"^(\w*)(?:\((.*)\))?!?: (.*)$", re.ASCII)  # JavaScript の \w と同じく ASCII だけ
MINOR_TYPES = ("feat", "revert")


def semver_level(commits):
    for record in commits.split("\x1e"):
        if not record.strip():
            continue
        subject = record.lstrip("\r\n").split("\x1f", 1)[0]
        match = HEADER.match(subject)
        if match and match.group(1) in MINOR_TYPES:
            return "minor"
    return "patch"


def main():
    print(semver_level(sys.stdin.read()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
