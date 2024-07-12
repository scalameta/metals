#!/usr/bin/env bash
set -eux

version=$1
argumentsRest=${@:2}
suffix=${argumentsRest:-}

coursier fetch \
  org.scalameta:metals_2.13:$version \
  org.scalameta:mtags_3.2.2:$version \
  org.scalameta:mtags_3.3.0:$version \
  org.scalameta:mtags_3.3.1:$version \
  org.scalameta:mtags_3.3.3:$version \
  org.scalameta:mtags_2.13.7:$version \
  org.scalameta:mtags_2.13.8:$version \
  org.scalameta:mtags_2.13.9:$version \
  org.scalameta:mtags_2.13.10:$version \
  org.scalameta:mtags_2.13.11:$version \
  org.scalameta:mtags_2.13.12:$version \
  org.scalameta:mtags_2.13.13:$version \
  org.scalameta:mtags_2.13.14:$version \
  org.scalameta:mtags_2.12.12:$version \
  org.scalameta:mtags_2.12.13:$version \
  org.scalameta:mtags_2.12.14:$version \
  org.scalameta:mtags_2.12.15:$version \
  org.scalameta:mtags_2.12.16:$version \
  org.scalameta:mtags_2.12.17:$version \
  org.scalameta:mtags_2.12.18:$version \
  org.scalameta:mtags_2.12.19:$version \
  org.scalameta:mtags_2.11.12:$version $suffix
