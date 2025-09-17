#!/bin/bash

function bloop_version {
  grep "val bloop =" project/V.scala | sed -n 's/.*"\(.*\)".*/\1/p'
}

export COURSIER_REPOSITORIES="central|ivy2local|https://central.sonatype.com/repository/maven-snapshots/"
export BLOOP_JAVA_OPTS="-Xss4m -XX:MaxInlineLevel=20 -XX:+UseZGC -XX:ZUncommitDelay=30 -XX:ZCollectionInterval=5 -XX:+IgnoreUnrecognizedVMOptions -Dbloop.ignore-sig-int=true -Xmx1G"

mkdir -p ~/.bloop
curl -Lo coursier https://git.io/coursier-cli && chmod +x coursier
if [[ "$OSTYPE" == "darwin"* ]]; then
  ./coursier launch -M bloop.cli.Bloop -r https://central.sonatype.com/repository/maven-snapshots/ ch.epfl.scala:bloop-cli_2.13:$(bloop_version) -- about || echo "Coursier launch failed, continuing..."
else
  timeout 60 ./coursier launch -M bloop.cli.Bloop -r https://central.sonatype.com/repository/maven-snapshots/ ch.epfl.scala:bloop-cli_2.13:$(bloop_version) -- about || echo "Coursier launch timed out or failed, continuing..."
fi

cat ~/.local/share/scalacli/bloop/daemon/output

rm .jvmopts
touch .jvmopts
echo "-Xss4m" >> .jvmopts
echo "-Xmx2G"  >> .jvmopts
echo "-XX:ReservedCodeCacheSize=1024m" >> .jvmopts
echo "-XX:+TieredCompilation" >> .jvmopts
echo "-Dfile.encoding=UTF-8" >> .jvmopts

sbt "$1"

# sbt must be the last command - its exit code signals if tests passed or not
