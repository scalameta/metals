#!/bin/bash

function bloop_version {
  grep "val bloop" build.sbt | sed 's|[^0-9.]||g'
}

wget -O bin/coursier https://git.io/coursier-cli && chmod +x bin/coursier \
&& bin/coursier launch ch.epfl.scala:bloopgun-core_2.12:$(bloop_version) -- about

sbt "$1"
# sbt must be the last command - its exit code signals if tests passed or not