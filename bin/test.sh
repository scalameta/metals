#!/bin/bash

bloopDir="bloop-server"

function bloop_version {
  grep "val bloop" build.sbt | sed 's|[^0-9.]||g'
}

[ -d "$bloopDir" ] && rm -rf "$bloopDir"
mkdir "$bloopDir"

wget -O $bloopDir/install.py https://github.com/scalacenter/bloop/releases/download/v$(bloop_version)/install.py \
&& python $bloopDir/install.py --dest $bloopDir

$bloopDir/bloop ng-stop
$bloopDir/bloop server &>/dev/null &

sbt "$1"
# sbt must be the last command - its exit code signals if tests passed or not