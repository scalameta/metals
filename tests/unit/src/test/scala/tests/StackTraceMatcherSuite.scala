package tests

import scala.meta.internal.metals.debug.StackTraceMatcher

class StackTraceMatcherSuite extends BaseSuite {
  val exampleStackTrace: List[String] =
    """|	at tests.StackTraceMatcherSuite.$anonfun$exampleStackTrace$1(StackTraceMatcherSuite.scala:10)
       |	at tests.StackTraceMatcherSuite.$anonfun$exampleStackTrace$1$adapted(StackTraceMatcherSuite.scala:10)
       |	at scala.collection.immutable.List.foreach(List.scala:392)
       |	at tests.StackTraceMatcherSuite.exampleStackTrace(StackTraceMatcherSuite.scala:10)
       |""".stripMargin.split("\n").toList.filter(_.nonEmpty)

  test("isStackTraceLine should return true for stack trace lines") {
    exampleStackTrace.foreach(line =>
      assert(
        StackTraceMatcher.isStackTraceLine(line),
        s"$line did not match stack trace regex",
      )
    )
  }
}
