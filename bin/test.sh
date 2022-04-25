#!/bin/bash

function bloop_version {
  grep "val bloop" build.sbt | sed -n 's/.*"\(.*\)".*/\1/p'
}

mkdir -p ~/.bloop
cp bin/bloop.json ~/.bloop/bloop.json
curl -Lo coursier https://git.io/coursier-cli && chmod +x coursier
./coursier launch ch.epfl.scala:bloopgun-core_2.13:$(bloop_version) -- about

rm .jvmopts
touch .jvmopts
echo "-Xss4m" >> .jvmopts 
echo "-Xmx1G"  >> .jvmopts 
echo "-XX:ReservedCodeCacheSize=1024m" >> .jvmopts
echo "-XX:+TieredCompilation" >> .jvmopts
echo "-Dfile.encoding=UTF-8" >> .jvmopts

sbt "$1"

# sbt must be the last command - its exit code signals if tests passed or not
