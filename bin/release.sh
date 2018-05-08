#!/usr/bin/env bash
set -ev

# release only on merge commits or tags
if [[ "$(git rev-list --merges HEAD^..HEAD)" || "$TRAVIS_TAG" ]]; then
    # configure git for using the deploy key for website publishing
    git config --global user.name "$USER"
    git config --global user.email "$TRAVIS_BUILD_NUMBER@$TRAVIS_COMMIT"
    chmod 600 project/travis-deploy-key
    eval "$(ssh-agent -s)"
    ssh-add project/travis-deploy-key

    sbt release

    sbt website/preprocess:preprocess

    cd website
    yarn install
    GIT_USER=git CURRENT_BRANCH=master USE_SSH=true yarn publish-gh-pages
else
    echo 'skipping release'
fi
