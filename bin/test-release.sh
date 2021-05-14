#!/usr/bin/env bash
set -eux

version=$1
argumentsRest=${@:2}
suffix=${argumentsRest:-}

coursier fetch \
  org.scalameta:mtags_3.0.0-RC1:$version \
  org.scalameta:mtags_3.0.0-RC2:$version \
  org.scalameta:mtags_3.0.0-RC3:$version \
  org.scalameta:mtags_3.0.0:$version \
  org.scalameta:metals_2.12:$version \
  org.scalameta:mtags_2.13.0:$version \
  org.scalameta:mtags_2.13.1:$version \
  org.scalameta:mtags_2.13.2:$version \
  org.scalameta:mtags_2.13.3:$version \
  org.scalameta:mtags_2.13.4:$version \
  org.scalameta:mtags_2.13.5:$version \
  org.scalameta:mtags_2.12.8:$version \
  org.scalameta:mtags_2.12.9:$version \
  org.scalameta:mtags_2.12.10:$version \
  org.scalameta:mtags_2.12.11:$version \
  org.scalameta:mtags_2.12.12:$version \
  org.scalameta:mtags_2.12.13:$version \
  org.scalameta:mtags_2.11.12:$version $suffix
