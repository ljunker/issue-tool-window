#!/usr/bin/env bash
# Wartet auf neue Maintainer-Beiträge an einem Issue oder Pull Request.
#
# Nutzung: entscheidung-abwarten.sh <owner/repo> <nummer> [intervall-sekunden=60] [max-minuten=0]
#
# Stichtag ist der jüngste Kommentar des Skills (Marker "<!-- github-issue-umsetzen:") im Thread.
# Berücksichtigt werden danach erstellte Kommentare, bei PRs auch Reviews und Zeilenkommentare,
# von Accounts mit der Rolle admin oder maintain. Beiträge mit Skill-Marker und Bots zählen nicht.
#
# Exit 0: Beiträge gefunden und chronologisch ausgegeben.
# Exit 2: max-minuten erreicht, ohne neuen Beitrag (0 = unbegrenzt warten).
# Exit 1: Fehler.
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Nutzung: $0 <owner/repo> <nummer> [intervall-sekunden=60] [max-minuten=0]" >&2
  exit 1
fi

repo=$1
nr=$2
interval=${3:-60}
max_min=${4:-0}
marker='<!-- github-issue-umsetzen:'
start=$(date +%s)
maintainers=" "
others=" "

is_pr=false
if gh api "repos/$repo/pulls/$nr" --silent 2>/dev/null; then
  is_pr=true
fi

since=$(gh api --paginate "repos/$repo/issues/$nr/comments" \
  --jq ".[] | select((.body // \"\") | contains(\"$marker\")) | .created_at" | sort | tail -n 1)
if [ -z "$since" ]; then
  echo "Kein Skill-Kommentar mit Marker in $repo#$nr gefunden. Zuerst Plan bzw. PR-Status kommentieren." >&2
  exit 1
fi

is_maintainer() {
  local login=$1 role
  case "$maintainers" in *" $login "*) return 0 ;; esac
  case "$others" in *" $login "*) return 1 ;; esac
  role=$(gh api "repos/$repo/collaborators/$login/permission" --jq '.role_name' 2>/dev/null || echo none)
  if [ "$role" = admin ] || [ "$role" = maintain ]; then
    maintainers="$maintainers$login "
    return 0
  fi
  others="$others$login "
  return 1
}

fetch() {
  local filter="select(.user.type != \"Bot\") | select((.body // \"\") | contains(\"$marker\") | not)"
  gh api --paginate "repos/$repo/issues/$nr/comments" --jq \
    ".[] | select(.created_at > \"$since\") | $filter
     | [.created_at, \"kommentar\", .user.login, .html_url, (.body // \"\")] | @tsv"
  if $is_pr; then
    gh api --paginate "repos/$repo/pulls/$nr/reviews" --jq \
      ".[] | select(.submitted_at != null and .submitted_at > \"$since\") | $filter
       | select((.body // \"\") != \"\" or .state != \"COMMENTED\")
       | [.submitted_at, \"review:\" + .state, .user.login, .html_url, (.body // \"\")] | @tsv"
    gh api --paginate "repos/$repo/pulls/$nr/comments" --jq \
      ".[] | select(.created_at > \"$since\") | $filter
       | [.created_at, \"zeilenkommentar:\" + .path + \":\" + ((.line // .original_line // 0) | tostring),
          .user.login, .html_url, (.body // \"\")] | @tsv"
  fi
}

while true; do
  found=0
  output=""
  while IFS=$'\t' read -r ts kind login url body; do
    [ -n "$login" ] || continue
    is_maintainer "$login" || continue
    found=1
    output+="--- $ts | $kind | @$login | $url"$'\n'"$(printf '%b' "$body")"$'\n\n'
  done < <(fetch | sort)

  if [ "$found" -eq 1 ]; then
    echo "Neue Maintainer-Beiträge in $repo#$nr seit $since:"
    echo
    printf '%s' "$output"
    exit 0
  fi

  if [ "$max_min" -gt 0 ] && [ $(( $(date +%s) - start )) -ge $(( max_min * 60 )) ]; then
    echo "Keine neuen Maintainer-Beiträge in $repo#$nr seit $since (nach $max_min Minuten)."
    exit 2
  fi
  sleep "$interval"
done
