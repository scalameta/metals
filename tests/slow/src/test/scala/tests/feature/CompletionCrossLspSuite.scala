package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}
import tests.BaseCompletionLspSuite

object CompletionCrossLspSuite
    extends BaseCompletionLspSuite("completion-cross") {

  if (super.isValidScalaVersionForEnv(V.scala211)) {
    testAsync("basic-211") {
      basicTest(V.scala211)
    }
  }

  if (super.isValidScalaVersionForEnv(V.scala213)) {
    testAsync("basic-213") {
      basicTest(V.scala213)
    }
  }
  testAsync("match-211") {
    matchKeywordTest(V.scala213)
  }
  testAsync("match-213") {
    matchKeywordTest(V.scala213)
  }
}
