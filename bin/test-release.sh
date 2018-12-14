#!/usr/bin/env bash
set -eux

version=$1

coursier fetch org.scalameta:metals_2.12:$version -r sonatype:releases
coursier fetch \
    "org.scalameta:sbt-metals;sbtVersion=1.0;scalaVersion=2.12:$version" \
    "org.scalameta:sbt-metals;sbtVersion=0.13;scalaVersion=2.10:$version" \
    --sbt-plugin-hack -r sonatype:releases
