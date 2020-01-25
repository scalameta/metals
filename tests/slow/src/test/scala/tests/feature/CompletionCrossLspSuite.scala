package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}
import tests.BaseCompletionLspSuite

class CompletionCrossLspSuite
    extends BaseCompletionLspSuite("completion-cross") {

  if (super.isValidScalaVersionForEnv(V.scala211)) {
    test("basic-211") {
      basicTest(V.scala211)
    }
  }

  if (super.isValidScalaVersionForEnv(V.scala213)) {
    test("basic-213") {
      basicTest(V.scala213)
    }
  }
  test("match-213".flaky) {
    matchKeywordTest(V.scala213)
  }
}
