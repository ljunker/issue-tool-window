#!/usr/bin/env bash
# Ermittelt die nächste Version aus Conventional Commits seit dem letzten stabilen Tag vX.Y.Z.
#   BREAKING CHANGE oder "typ!:" -> Major, "feat:" -> Minor, "fix:"/"perf:" -> Patch, sonst kein Release.
# Aufruf: next-version.sh <auto|patch|minor|major> <Ausgabeverzeichnis>
# Schreibt version, release (true/false) und last_tag nach $GITHUB_OUTPUT (falls gesetzt) bzw. stdout,
# sowie release-notes.md und change-notes.html ins Ausgabeverzeichnis.
set -euo pipefail

bump_input="${1:-auto}"
out_dir="${2:-build/release}"
mkdir -p "$out_dir"

last_tag="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' 2>/dev/null || true)"
if [[ -n "$last_tag" ]]; then
  range="$last_tag..HEAD"
  base="${last_tag#v}"
else
  range="HEAD"
  base="0.0.0"
fi
IFS=. read -r major minor patch <<<"$base"

type_re='^([a-z]+)(\([^)]*\))?(!)?: (.+)$'
level=0 # 0 = nichts, 1 = patch, 2 = minor, 3 = major
features=() fixes=() breaking=()

while IFS= read -r -d $'\x1e' entry; do
  entry="${entry#$'\n'}"
  subject="${entry%%$'\x1f'*}"
  body="${entry#*$'\x1f'}"
  [[ "$subject" =~ $type_re ]] || continue
  type="${BASH_REMATCH[1]}" bang="${BASH_REMATCH[3]}" text="${BASH_REMATCH[4]}"
  if [[ -n "$bang" || "$body" == *"BREAKING CHANGE"* ]]; then
    breaking+=("$text"); level=3
  fi
  case "$type" in
    feat) features+=("$text"); (( level < 2 )) && level=2 ;;
    fix|perf) fixes+=("$text"); (( level < 1 )) && level=1 ;;
  esac
done < <(git log --no-merges --format='%s%x1f%b%x1e' $range)

case "$bump_input" in
  major) level=3 ;;
  minor) level=2 ;;
  patch) level=1 ;;
  auto) ;;
  *) echo "Unbekannte Bump-Art: $bump_input" >&2; exit 1 ;;
esac

case "$level" in
  3) version="$((major + 1)).0.0" ;;
  2) version="$major.$((minor + 1)).0" ;;
  1) version="$major.$minor.$((patch + 1))" ;;
  *) version="$base" ;;
esac
release=$([[ "$level" -gt 0 ]] && echo true || echo false)

html_escape() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' <<<"$1"; }

section() { # Titel, Einträge...
  local title="$1"; shift
  (( $# )) || return 0
  printf '### %s\n\n' "$title" >>"$out_dir/release-notes.md"
  printf '<h3>%s</h3>\n<ul>\n' "$title" >>"$out_dir/change-notes.html"
  for item in "$@"; do
    printf -- '- %s\n' "$item" >>"$out_dir/release-notes.md"
    printf '  <li>%s</li>\n' "$(html_escape "$item")" >>"$out_dir/change-notes.html"
  done
  printf '\n' >>"$out_dir/release-notes.md"
  printf '</ul>\n' >>"$out_dir/change-notes.html"
}

: >"$out_dir/release-notes.md"
: >"$out_dir/change-notes.html"
section "Breaking Changes" ${breaking[@]+"${breaking[@]}"}
section "Features" ${features[@]+"${features[@]}"}
section "Fixes" ${fixes[@]+"${fixes[@]}"}

{
  echo "version=$version"
  echo "release=$release"
  echo "last_tag=$last_tag"
} | tee -a "${GITHUB_OUTPUT:-/dev/null}"
