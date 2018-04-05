#!/usr/bin/env sh
set -ev

# release only on merge commits or tags
if [[ "$(git rev-list --merges HEAD^..HEAD)" || "$TRAVIS_TAG" ]]; then
    sbt release
else
    echo 'skipping release'
fi
