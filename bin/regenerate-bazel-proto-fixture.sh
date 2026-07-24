#!/usr/bin/env bash
# Regenerates the `bazel query --output=streamed_proto` fixture consumed by
# `tests.bazel.BazelTargetsProtoDumpRealOutputSuite`:
#
#   tests/unit/src/test/resources/bazel/rules-scala-fullinfo-fragments.pb
#
# Run it from INSIDE a bazelbuild/rules_scala checkout (commit 156e65c /
# v7.2.5-5-g156e65c, Bazel 7.7.1). It does no validation — it just runs the
# importer's exact full-information query over the nine pinned targets and
# writes the raw binary stream.
#
# Usage (from within the rules_scala repo):
#   /path/to/metals/bin/regenerate-bazel-proto-fixture.sh > rules-scala-fullinfo-fragments.pb
#   /path/to/metals/bin/regenerate-bazel-proto-fixture.sh /path/to/metals/tests/unit/src/test/resources/bazel/rules-scala-fullinfo-fragments.pb
set -euo pipefail

# The nine hand-picked targets: six rules plus a SOURCE_FILE, a GENERATED_FILE
# and a PACKAGE_GROUP, chosen to cover the decoder's edge cases with no overlap.
TARGETS="\
//third_party/dependency_analyzer/src/main:scala_version \
//src/java/io/bazel/rulesscala/scalac/reporter:reporter \
//third_party/dependency_analyzer/src/main/io/bazel/rulesscala/dependencyanalyzer/compiler:dep_reporting_compiler \
//src/java/io/bazel/rulesscala/scalac:scalac_files \
//src/java/io/bazel/rulesscala/scalac:scalac \
//third_party/dependency_analyzer/src/test:scalac_dependency_test \
//java_stub_template/file:file.txt \
//scala:libPlaceHolderClassToCreateEmptyJarForScalaImport.jar \
@bazel_tools//src/main/cpp/util:ijar"

# The importer's production query flags (see BazelQuery.scala) plus
# `--proto:locations=false` to keep the fixture compact.
# `--keep_going` exits 3 on partial success, which is expected here,
# so don't let `set -e` trip on it.
# stdout is the raw binary; a path arg (if given) receives it, else it
# goes to this script's stdout for the caller to redirect.
run() {
  bazel query \
    --output=streamed_proto \
    --proto:flatten_selects=false \
    --proto:output_rule_attrs=srcs,scalacopts,javacopts,scala_version,jars,srcjar \
    --proto:locations=false \
    --keep_going \
    "set(${TARGETS})"
}

if [[ $# -ge 1 ]]; then
  run > "$1" || { rc=$?; [[ $rc -eq 3 ]] || exit $rc; }
  echo "Wrote $(wc -c < "$1") bytes to $1" >&2
else
  run || { rc=$?; [[ $rc -eq 3 ]] || exit $rc; }
fi
