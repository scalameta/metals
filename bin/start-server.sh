#!/usr/bin/env sh

COURSIER_DIR="$HOME/.coursier"
COURSIER_PATH="$COURSIER_DIR/coursier"

test -e "$COURSIER_PATH" || ( \
  mkdir -p "$COURSIER_DIR" && \
  curl -Lso "$COURSIER_PATH" https://git.io/vgvpD && \
  chmod +x "$COURSIER_PATH" \
)

if [ "x$JAVA_HOME" = "x" ]; then
  JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 >/dev/null \
                | grep java.home \
                | tr -d '[:space:]' \
                | cut -d'=' -f2)
fi

TOOLS_JAR="$JAVA_HOME/lib/tools.jar"
LAUNCH="$COURSIER_PATH launch -r sonatype:releases \
                              -J $TOOLS_JAR org.scalameta:metaserver_2.12:0.1-SNAPSHOT \
                              -M scala.meta.languageserver.Main"

eval "$LAUNCH"
