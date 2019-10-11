#!/bin/bash

function bloop_version {
  cat build.sbt | grep "val bloop" | sed 's|[^0-9.]||g'
}

wget -O bin/coursier https://git.io/coursier-cli && chmod +x bin/coursier \
&& bin/coursier launch ch.epfl.scala:bloopgun-core_2.12:$(bloop_version) -- server &>bloop.log &

sbt "$1"

cat bloop.log