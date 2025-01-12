#!/usr/bin/env sh
# NOTE: Run this from the root of metals ./bin/update-millw.sh

root_url=https://raw.githubusercontent.com/com-lihaoyi/mill/main

curl $root_url/mill > $PWD/metals/src/main/resources/mill
curl $root_url/mill.bat > $PWD/metals/src/main/resources/mill.bat
