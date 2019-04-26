#!/usr/bin/env bash
set -eux

version=$1

coursier fetch \
  org.scalameta:metals_2.12:$version \
  org.scalameta:mtags_2.12.8:$version \
  org.scalameta:mtags_2.12.7:$version \
  org.scalameta:mtags_2.12.6:$version \
  org.scalameta:mtags_2.12.5:$version \
  org.scalameta:mtags_2.12.4:$version \
  org.scalameta:mtags_2.11.12:$version \
  org.scalameta:mtags_2.11.11:$version \
  org.scalameta:mtags_2.11.10:$version \
  org.scalameta:mtags_2.11.9:$version \
  -r sonatype:public
coursier fetch \
    "org.scalameta:sbt-metals;sbtVersion=1.0;scalaVersion=2.12:$version" \
    "org.scalameta:sbt-metals;sbtVersion=0.13;scalaVersion=2.10:$version" \
    --sbt-plugin-hack -r sonatype:public
