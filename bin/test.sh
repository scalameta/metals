#!/bin/bash

function bloop_version {
  grep "val bloop =" project/V.scala | sed -n 's/.*"\(.*\)".*/\1/p'
}

export COURSIER_REPOSITORIES="central|sonatype:snapshots"
export BLOOP_JAVA_OPTS="-Xss4m -Xmx2G -XX:MaxInlineLevel=20 -XX:+UseZGC -XX:ZUncommitDelay=30 -XX:ZCollectionInterval=5 -XX:+IgnoreUnrecognizedVMOptions -Dbloop.ignore-sig-int=true"

mkdir -p ~/.bloop
curl -Lo coursier https://git.io/coursier-cli && chmod +x coursier
./coursier launch -M bloop.cli.Bloop -r sonatype:snapshots ch.epfl.scala:bloop-cli_2.13:$(bloop_version) -- about

cat ~/.local/share/scalacli/bloop/daemon/output

rm .jvmopts
touch .jvmopts
echo "-Xss4m" >> .jvmopts
echo "-Xmx4G"  >> .jvmopts
echo "-XX:ReservedCodeCacheSize=1024m" >> .jvmopts
echo "-XX:+TieredCompilation" >> .jvmopts
echo "-Dfile.encoding=UTF-8" >> .jvmopts
echo "-XX:+HeapDumpOnOutOfMemoryError" >> .jvmopts
echo "-XX:+ExitOnOutOfMemoryError" >> .jvmopts

echo $PWD

sbt "$1"

# sbt must be the last command - its exit code signals if tests passed or not
