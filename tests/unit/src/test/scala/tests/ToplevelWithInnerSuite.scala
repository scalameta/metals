package tests

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Semanticdbs

abstract class ToplevelWithInnerSuite(
    inputProperties: => InputProperties,
    directory: String,
    dialect: Dialect,
) extends DirectoryExpectSuite(directory) {

  override lazy val input: InputProperties = inputProperties

  def testCases(): List[ExpectTestCase] = {
    input.allFiles.collect {
      case file if file.isScala =>
        ExpectTestCase(
          file,
          { () =>
            val input = file.input
            val toplevelMtags =
              Mtags.allToplevels(input, dialect, includeMembers = false)
            Semanticdbs.printTextDocument(toplevelMtags)
          },
        )
    }
  }
}

class ToplevelWithInnerScala2Suite
    extends ToplevelWithInnerSuite(
      InputProperties.scala2(),
      "toplevel-with-inner",
      dialects.Scala213,
    )
@munit.IgnoreSuite
class ToplevelWithInnerScala3Suite
    extends ToplevelWithInnerSuite(
      InputProperties.scala3(),
      "toplevel-with-inner-scala3",
      dialects.Scala3,
    )
