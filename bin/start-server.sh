#!/usr/bin/env sh

COURSIER_DIR="$HOME/.coursier"
COURSIER_PATH="$COURSIER_DIR/coursier"

test -e "$COURSIER_PATH" || ( \
  mkdir -p "$COURSIER_DIR" && \
  curl -Lso "$COURSIER_PATH" https://git.io/vgvpD && \
  chmod +x "$COURSIER_PATH" \
)

LAUNCH="$COURSIER_PATH launch -r bintray:scalameta/maven \
                              org.scalameta:metals_2.12:0.1-SNAPSHOT \
                              -M scala.meta.metals.Main"

eval "$LAUNCH"
