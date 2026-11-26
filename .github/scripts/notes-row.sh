#!/usr/bin/env bash
# Sets one row of the table in a release's notes (the table android-release.yml writes) and prints the new notes.
#
#   notes-row.sh <notes-file> <row name> <cell> [after-row]
#
# Replaces the row "| <row name> | … |" (or a line "<row name>: …" from an earlier run) where it is, dropping any
# duplicates. Notes without one get the row after the row named <after-row>, or, without that one either, a line
# "<row name>: <cell>" at the end. The name and cell are passed as data, never as code.
set -euo pipefail

notes="${1:?usage: notes-row.sh <notes-file> <row name> <cell> [after-row]}"
ROW_NAME="${2:?row name}" CELL="${3:?cell}" AFTER="${4:-}" awk '
  function is_row(line) { return index(line, "| " name " |") == 1 }
  function is_line(line) { return index(line, name ": ") == 1 }
  BEGIN { name = ENVIRON["ROW_NAME"]; cell = ENVIRON["CELL"]; after = ENVIRON["AFTER"] }
  { lines[NR] = $0 }
  END {
    for (i = 1; i <= NR; i++) if (is_row(lines[i]) || is_line(lines[i])) { found = i; break }
    if (!found && after != "") for (i = 1; i <= NR; i++) if (index(lines[i], "| " after " |") == 1) { anchor = i; break }
    for (i = 1; i <= NR; i++) {
      if (i == found) { print (is_row(lines[i]) ? "| " name " | " cell " |" : name ": " cell); continue }
      if (found && (is_row(lines[i]) || is_line(lines[i]))) continue
      print lines[i]
      if (i == anchor) print "| " name " | " cell " |"
    }
    if (!found && !anchor) { print ""; print name ": " cell }
  }
' "$notes"
