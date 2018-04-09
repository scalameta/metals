#!/usr/bin/env bash
set -eux

if [[ $(git diff master --name-only | grep sbt-metals) ]]; then
  echo "Diff detected in sbt-metals, running scripted tests"
  sbt ^sbt-metals/scripted
else
  echo "No diff detected in sbt-metals, skipping scripted tests"
fi
