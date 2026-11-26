#!/usr/bin/env bash
# Prints the "What's new" and "Contributors" part of a Pairline Bridge release's notes (D-080).
#
#   GH_REPO=owner/name .github/scripts/release-notes.sh <version> <path to android/CHANGELOG.md>
#
# What's new: the CHANGELOG.md sections of every version after the newest *published* android-v* release that is
# older than this one, up to this one, so a release that follows unpublished drafts still lists everything people
# get. Fails when <version> has no section.
# Contributors: the GitHub accounts (a name when a commit isn't linked to an account) of the authors of the commits
# in that range that touch android/ or protocol/, most commits first. Bots are left out.
#
# Needs the git history with tags (actions/checkout with fetch-depth: 0) and gh (GH_TOKEN on CI). Used by
# .github/workflows/android-release.yml; run it by hand to rebuild an older release's notes.
set -euo pipefail

version="${1:?usage: release-notes.sh <version> <changelog>}"
changelog="${2:?usage: release-notes.sh <version> <changelog>}"
repo="${GH_REPO:?set GH_REPO to owner/name}"
tag="android-v$version"

# a < b as versions (1.9.0 < 1.10.0).
version_lt() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" = "$1" ]
}

# The body of "## <version> …" up to the next "## " heading, without blank lines at either end. Wrapped lines are
# joined: GitHub shows every line break in release notes.
section() {
  awk -v v="$1" '
    function flush() { if (cur != "") { for (; blanks > 0; blanks--) print ""; print cur; cur = "" } }
    /^## / { if (p) exit; if ($2 == v) p = 1; next }
    p {
      if ($0 ~ /^[[:space:]]*$/) { flush(); if (started) blanks++; next }
      if (cur != "" && $0 ~ /^[[:space:]]+/ && $0 !~ /^[[:space:]]*([-*]|[0-9]+\.) /) {
        line = $0; sub(/^[[:space:]]+/, "", line); cur = cur " " line; next
      }
      flush(); started = 1; cur = $0
    }
    END { flush() }' "$changelog"
}

if [ -z "$(section "$version")" ]; then
  echo "::error::android/CHANGELOG.md has no '## $version' section. Add what's new in $version before tagging." >&2
  exit 1
fi

# The newest published (not draft) release older than this version.
base=""
for t in $(gh release list --repo "$repo" --exclude-drafts --limit 200 --json tagName --jq '.[].tagName'); do
  case "$t" in android-v*) ;; *) continue ;; esac
  v="${t#android-v}"
  if version_lt "$v" "$version" && { [ -z "$base" ] || version_lt "$base" "$v"; }; then base="$v"; fi
done

# Changelog versions in (base, version], newest first.
versions=()
while read -r v; do
  [ -n "$v" ] || continue
  if { [ "$v" = "$version" ] || version_lt "$v" "$version"; } && { [ -z "$base" ] || version_lt "$base" "$v"; }; then
    versions+=("$v")
  fi
done < <(awk '/^## [0-9]/ { print $2 }' "$changelog" | sort -rV)

echo "### What's new"
echo
if [ "${#versions[@]}" -eq 1 ]; then
  section "$version"
else
  first=1
  for v in "${versions[@]}"; do
    [ "$first" -eq 1 ] || echo
    first=0
    echo "#### $v"
    echo
    section "$v"
  done
fi
echo

# Commit authors in the range, through GitHub so each maps to an account.
if [ -n "$base" ]; then
  from="android-v$base"
  range="$from..$tag"
else
  from="$(git rev-list --max-parents=0 "$tag" | tail -1)"
  range="$tag"
fi
authors="$(mktemp)"
trap 'rm -f "$authors"' EXIT
gh api --paginate "repos/$repo/compare/$from...$tag?per_page=100" \
  --jq '.commits[] | [.sha, (.author.login // ""), .commit.author.name] | @tsv' > "$authors"
if [ -z "$base" ]; then
  gh api "repos/$repo/commits/$from" --jq '[.sha, (.author.login // ""), .commit.author.name] | @tsv' >> "$authors"
fi

contributors="$(
  git log --format=%H "$range" -- ':/android' ':/protocol' | while read -r sha; do
    line="$(grep -m1 "^$sha	" "$authors" || true)"
    [ -n "$line" ] || continue
    login="$(printf '%s' "$line" | cut -f2)"
    name="$(printf '%s' "$line" | cut -f3)"
    case "$login" in *'[bot]') continue ;; esac
    if [ -n "$login" ]; then echo "@$login"; else echo "$name"; fi
  done | sort | uniq -c | sort -k1,1nr -k2 | sed 's/^ *[0-9]* //'
)"

if [ -n "$contributors" ]; then
  echo "### Contributors"
  echo
  printf '%s\n' "$contributors" | paste -sd ',' - | sed 's/,/, /g'
  echo
fi
