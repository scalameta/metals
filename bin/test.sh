#!/bin/bash

function bloop_version {
  grep "val bloop" build.sbt | sed -n 's/.*"\(.*\)".*/\1/p'
}

mkdir -p ~/.bloop
touch ~/.bloop/.jvmopts
echo "-Xss16m" >> ~/.bloop/.jvmopts 
echo "-Xmx1G"  >> ~/.bloop/.jvmopts 

curl -Lo coursier https://git.io/coursier-cli && chmod +x coursier
./coursier launch ch.epfl.scala:bloopgun-core_2.12:$(bloop_version) -- about

rm .jvmopts
touch .jvmopts
echo "-Xss4m" >> .jvmopts 
echo "-Xmx1G"  >> .jvmopts 
echo "-XX:ReservedCodeCacheSize=1024m" >> .jvmopts
echo "-XX:+TieredCompilation" >> .jvmopts
echo "-XX:+CMSClassUnloadingEnabled" >> .jvmopts
echo "-Dfile.encoding=UTF-8" >> .jvmopts

sbt "$1"

# sbt must be the last command - its exit code signals if tests passed or not