#!/usr/bin/env sh

METALS_VERSION=${1:-"SNAPSHOT"}
COURSIER_DIR="$HOME/.coursier"
COURSIER_PATH="$COURSIER_DIR/coursier"

test -e "$COURSIER_PATH" || ( \
  mkdir -p "$COURSIER_DIR" && \
  curl -Lso "$COURSIER_PATH" https://git.io/vgvpD && \
  chmod +x "$COURSIER_PATH" \
)

LAUNCH="$COURSIER_PATH launch -r bintray:scalameta/maven \
                              org.scalameta:metals_2.12:$METALS_VERSION \
                              -M scala.meta.metals.Main"

eval "$LAUNCH"
