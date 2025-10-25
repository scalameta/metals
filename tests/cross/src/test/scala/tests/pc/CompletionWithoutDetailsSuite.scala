package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig

import tests.BaseCompletionSuite

class CompletionWithoutDetailsSuite extends BaseCompletionSuite {

  override def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      isDetailIncludedInLabel = false
    )

  check(
    "scope",
    """
      |object A {
      |  Lis@@
      |}""".stripMargin,
    """|List
       |List
       |List
       |JList
       |ListUI
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|List
           |LazyList
           |List
           |List
           |JList
           |""".stripMargin
    ),
    includeDetail = false,
    topLines = Some(5)
  )

  check(
    "scope-detail",
    """
      |object A {
      |  Lis@@
      |}""".stripMargin,
    """|List scala.collection.immutable
       |List java.awt
       |List java.util
       |JList javax.swing
       |ListUI javax.swing.plaf
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|List scala.collection.immutable
           |LazyList scala.collection.immutable
           |List java.awt
           |List java.util
           |JList javax.swing
           |""".stripMargin
    ),
    includeDetail = true,
    topLines = Some(5)
  )

  check(
    "member",
    """
      |object A {
      |  List.emp@@
      |}""".stripMargin,
    """
      |empty
      |""".stripMargin,
    includeDetail = false
  )

  check(
    "extension",
    """
      |object A {
      |  "".stripSu@@
      |}""".stripMargin,
    """|stripSuffix
       |""".stripMargin,
    includeDetail = false
  )

  check(
    "tparam",
    """
      |class Foo[A] {
      |  def identity[B >: A](a: B): B = a
      |}
      |object Foo {
      |  new Foo[Int].ident@@
      |}""".stripMargin,
    """|identity
       |""".stripMargin,
    includeDetail = false
  )

  check(
    "tparam1",
    """
      |class Foo[A] {
      |  def identity(a: A): A = a
      |}
      |object Foo {
      |  new Foo[Int].ident@@
      |}""".stripMargin,
    """|identity
       |""".stripMargin,
    includeDetail = false
  )

  check(
    "tparam2",
    """
      |object A {
      |  Map.empty[Int, String].getOrEl@@
      |}
      |""".stripMargin,
    """|getOrElse
       |""".stripMargin,
    includeDetail = false
  )

  check(
    "pkg",
    """
      |import scala.collection.conc@@
      |""".stripMargin,
    """|concurrent
       |""".stripMargin,
    includeDetail = false
  )
}
