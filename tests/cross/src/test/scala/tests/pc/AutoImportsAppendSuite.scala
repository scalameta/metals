package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.ScalaImportsPlacement

import tests.BaseAutoImportsSuite

/**
 * Test suite for APPEND_LAST import placement mode.
 * This ensures the traditional append behavior works correctly.
 */
class AutoImportsAppendSuite extends BaseAutoImportsSuite {

  override protected def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      snippetAutoIndent = false,
      scalaImportsPlacement = ScalaImportsPlacement.APPEND_LAST
    )

  checkEdit(
    "append-after-existing",
    """|package a
       |
       |import scala.collection.mutable.Set
       |
       |object A {
       |  Set.empty
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    // With APPEND_LAST, new imports are appended at the end of the import block
    """|package a
       |
       |import scala.collection.mutable.Set
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  Set.empty
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  checkEdit(
    "append-no-existing-imports",
    """|package a
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  checkEdit(
    "append-multiple-existing",
    """|package a
       |
       |import java.util.Random
       |import scala.util.Try
       |
       |object A {
       |  Random
       |  Try
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    // New import appended at the end
    """|package a
       |
       |import java.util.Random
       |import scala.util.Try
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  Random
       |  Try
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  checkEdit(
    "append-with-grouped-import",
    """|package a
       |
       |import scala.collection.mutable.{Buffer, Set}
       |
       |object A {
       |  Buffer
       |  Set
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    // APPEND_LAST does not insert into grouped imports, just appends
    """|package a
       |
       |import scala.collection.mutable.{Buffer, Set}
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  Buffer
       |  Set
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )
}
