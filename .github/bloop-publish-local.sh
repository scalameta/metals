#!/usr/bin/env bash
set -e

git clone https://github.com/alexarchambault/bloop.git -b unix-domain-socket-dev
cd bloop
git submodule update --init --recursive
.github/nailgun-publish-local.sh
sbt stuff/publishLocal
