package tests.feature

import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbolQuickFix
import scala.meta.internal.metals.{BuildInfo => V}

import tests.codeactions.BaseCodeActionLspSuite

/**
 * A member exposed by the package object of a *workspace* module (a sibling
 * sbt project rather than a dependency jar) has no classfile of its own, and
 * the package object itself is surfaced as a workspace symbol rather than a
 * classpath `package.class`. It can therefore only be discovered through the
 * package-object search reading workspace symbols (issue #2583); this suite is
 * the regression test for that discovery path across build targets.
 *
 * The package-object search is implemented in the Scala 2 presentation
 * compiler only, so these run against Scala 2.13.
 */
class ImportMissingPackageObjectMemberCrossLspSuite
    extends BaseCodeActionLspSuite("importMissingPackageObjectMember-cross") {

  if (super.isValidScalaVersionForEnv(V.scala213)) {
    checkEdit(
      "term-from-workspace-package-object",
      s"""|/metals.json
          |{
          |  "a": {"scalaVersion": "${V.scala213}"},
          |  "b": {"scalaVersion": "${V.scala213}", "dependsOn": ["a"]}
          |}
          |/a/src/main/scala/mylib/package.scala
          |package object mylib {
          |  val Foo: Int = 1
          |}
          |/b/src/main/scala/x/B.scala
          |package x
          |object B {
          |  val f = <<Foo>>
          |}
          |""".stripMargin,
      s"""|${ImportMissingSymbol.title("Foo", "mylib")}
          |${CreateNewSymbol.title("Foo")}
          |""".stripMargin,
      s"""|package x
          |
          |import mylib.Foo
          |object B {
          |  val f = Foo
          |}
          |""".stripMargin,
      kind = List(ImportMissingSymbolQuickFix.kind),
    )

    checkEdit(
      "type-from-workspace-package-object",
      s"""|/metals.json
          |{
          |  "a": {"scalaVersion": "${V.scala213}"},
          |  "b": {"scalaVersion": "${V.scala213}", "dependsOn": ["a"]}
          |}
          |/a/src/main/scala/mylib/package.scala
          |package object mylib {
          |  type Bar = String
          |}
          |/b/src/main/scala/x/B.scala
          |package x
          |object B {
          |  def f: <<Bar>> = ""
          |}
          |""".stripMargin,
      s"""|${ImportMissingSymbol.title("Bar", "mylib")}
          |${CreateNewSymbol.title("Bar")}
          |""".stripMargin,
      s"""|package x
          |
          |import mylib.Bar
          |object B {
          |  def f: Bar = ""
          |}
          |""".stripMargin,
      kind = List(ImportMissingSymbolQuickFix.kind),
    )

    // a member inherited into the package object through a mixin parent (the
    // doobie shape) is also classfile-less and discovered only via this path
    checkEdit(
      "inherited-from-workspace-package-object",
      s"""|/metals.json
          |{
          |  "a": {"scalaVersion": "${V.scala213}"},
          |  "b": {"scalaVersion": "${V.scala213}", "dependsOn": ["a"]}
          |}
          |/a/src/main/scala/mylib/Aliases.scala
          |package mylib
          |trait Aliases {
          |  val Baz: Int = 2
          |}
          |/a/src/main/scala/mylib/package.scala
          |package object mylib extends Aliases
          |/b/src/main/scala/x/B.scala
          |package x
          |object B {
          |  val f = <<Baz>>
          |}
          |""".stripMargin,
      s"""|${ImportMissingSymbol.title("Baz", "mylib")}
          |${CreateNewSymbol.title("Baz")}
          |""".stripMargin,
      s"""|package x
          |
          |import mylib.Baz
          |object B {
          |  val f = Baz
          |}
          |""".stripMargin,
      kind = List(ImportMissingSymbolQuickFix.kind),
    )
  }
}
