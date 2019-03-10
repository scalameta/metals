package tests

import scala.meta.internal.metals.{BuildInfo => V}

object CompletionCrossSlowSuite
    extends BaseCompletionSlowSuite("completion-cross") {
  testAsync("basic-211") {
    basicTest(V.scala211)
  }
}
