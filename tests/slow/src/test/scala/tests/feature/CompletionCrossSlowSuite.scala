package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}
import tests.BaseCompletionSlowSuite

object CompletionCrossSlowSuite
    extends BaseCompletionSlowSuite("completion-cross") {
  testAsync("basic-211") {
    basicTest(V.scala211)
  }
  testAsync("basic-213") {
    basicTest(V.scala213)
  }
}
