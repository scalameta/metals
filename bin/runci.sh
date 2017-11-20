#!/bin/sh
set -eux
TEST=${1}

case "$TEST" in
  "scalafmt" )
    ./scalafmt --test
    ;;
  * )
    sbt "*:scalametaEnableCompletions" test
    ;;
esac

