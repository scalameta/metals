#!/usr/bin/env sh
# NOTE: Run this from the root of metals ./bin/update-millw.sh

root_url=https://raw.githubusercontent.com/lefou/millw/main

curl $root_url/millw > $PWD/metals/src/main/resources/millw
curl $root_url/millw.bat > $PWD/metals/src/main/resources/millw.bat
