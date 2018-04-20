package tests.refactoring

import scala.meta.metals.Uri
import scala.meta.metals.refactoring.OrganizeImports
import scala.meta.metals.refactoring.TextEdits
import org.langmeta.inputs.Input
import tests.CompilerSuite

// This test suite is not supposed to test the actual scalafix implementation,
// it is only supposed to check that the conversion from scalafix.Patch to
// languageserver.TextEdit is accurate.
object RemoveUnusedImportsTest extends CompilerSuite {
  def check(name: String, original: String, expectedEdits: String): Unit = {
    test(name) {
      val uri = Uri.file(name)
      val document = toDocument(uri.value, original)
      val edits = OrganizeImports
        .removeUnused(uri, document)
        .edit
        .changes(uri.value)
        .toList
      val obtained =
        TextEdits.applyToInput(Input.VirtualFile(name, original), edits)
      assertNoDiff(obtained, expectedEdits)
    }
  }

  check(
    "remove-all",
    """
      |import scala.concurrent.{Future, Await}
      |import scala.math.max
    """.stripMargin,
    ""
  )

  check(
    "grouped",
    """
      |import scala.concurrent.{Future, Await}
      |import scala.math.max
      |object a {
      |  max(1, 2)
      |  Future.successful(1)
      |}
    """.stripMargin,
    """
      |import scala.concurrent.Future
      |import scala.math.max
      |object a {
      |  max(1, 2)
      |  Future.successful(1)
      |}
    """.stripMargin
  )

}
