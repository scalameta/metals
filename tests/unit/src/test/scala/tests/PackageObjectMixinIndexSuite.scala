package tests

import java.nio.file.Files

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.io.AbsolutePath

/**
 * Tests that indexing a source jar copies type members inherited from mixin
 * parents into the package object's symbol space, so that import suggestions
 * can offer e.g. `import doobie.Transactor` for an alias defined in a trait
 * mixed into `package object doobie` (issue #2583).
 */
class PackageObjectMixinIndexSuite extends BaseSuite {

  private def checkMembers(
      name: String,
      layout: String,
      expected: List[String],
      dialect: Dialect = dialects.Scala213,
  )(implicit loc: munit.Location): Unit =
    test(name) {
      val tmp = Files.createTempDirectory("metals-mixin-index")
      val zip = AbsolutePath(tmp.resolve("sources.zip"))
      FileIO.withJarFileSystem(zip, create = true, close = true) { root =>
        FileLayout.fromString(layout, root)
      }
      val index = OnDemandSymbolIndex.empty()(EmptyReportContext)
      val obtained = index
        .addSourceJar(zip, dialect)
        .flatMap(_.toplevelMembers)
        .map(_.symbol)
        .distinct
        .sorted
      assertNoDiff(
        obtained.mkString("\n"),
        expected.sorted.mkString("\n"),
      )
    }

  checkMembers(
    "parent-in-same-package",
    """|/doobie/package.scala
       |package object doobie extends Aliases
       |/doobie/Aliases.scala
       |package doobie
       |trait Aliases {
       |  type Transactor[M[_]] = doobie.util.transactor.Transactor[M]
       |  val Transactor = doobie.util.transactor.Transactor
       |}
       |/doobie/util/transactor.scala
       |package doobie.util
       |object transactor {
       |  class Transactor[M[_]]
       |}
       |""".stripMargin,
    List("doobie/package.Transactor#"),
  )

  checkMembers(
    "parent-in-same-file",
    """|/x/package.scala
       |package x
       |
       |package object y extends YAliases
       |
       |trait YAliases {
       |  type Y1 = Int
       |}
       |""".stripMargin,
    List("x/y/package.Y1#"),
  )

  checkMembers(
    "parent-in-subpackage",
    """|/d/package.scala
       |package object d extends util.DAliases
       |/d/util/DAliases.scala
       |package d.util
       |trait DAliases {
       |  type D1 = String
       |}
       |""".stripMargin,
    List("d/package.D1#"),
  )

  // The parents share a simple name (`Modules`) but are written qualified, so
  // each resolves to a distinct trait and both contribute members (#2583).
  checkMembers(
    "qualified-parents-disambiguated",
    """|/e/package.scala
       |package object e extends hi.Modules with free.Modules
       |/e/hi/Modules.scala
       |package e.hi
       |trait Modules {
       |  type M1 = Int
       |}
       |/e/free/Modules.scala
       |package e.free
       |trait Modules {
       |  type M2 = Int
       |}
       |""".stripMargin,
    List("e/package.M1#", "e/package.M2#"),
  )

  // The package object extends the qualified `free.Types`, but a different
  // `Types` exists in the same package. The qualified path must win, so only
  // the members of `doobie.free.Types` are copied (the real doobie shape that
  // exposes e.g. `doobie.ConnectionIO`), see #2583.
  checkMembers(
    "qualified-parent-over-same-package-homonym",
    """|/doobie/package.scala
       |package object doobie extends free.Types
       |/doobie/Types.scala
       |package doobie
       |trait Types {
       |  type NotExposed = Int
       |}
       |/doobie/free/Types.scala
       |package doobie.free
       |trait Types {
       |  type ConnectionIO = String
       |}
       |""".stripMargin,
    List("doobie/package.ConnectionIO#"),
  )

  // A qualified parent that is external to the jar (`cats.syntax.AllSyntax`)
  // must not be confused with an unrelated local trait of the same simple
  // name; the simple-name fallback is for unqualified parents only. #2583
  checkMembers(
    "qualified-external-parent-not-confused-with-local",
    """|/p/package.scala
       |package object p extends cats.syntax.AllSyntax
       |/p/AllSyntax.scala
       |package p
       |trait AllSyntax {
       |  type Local = Int
       |}
       |""".stripMargin,
    List(),
  )

  // An unqualified parent whose simple name matches several traits in the jar
  // cannot be resolved unambiguously, so it is skipped (no wrong members).
  checkMembers(
    "unqualified-ambiguous-parent-skipped",
    """|/e2/package.scala
       |package object e2 extends Modules
       |/e2/hi/Modules.scala
       |package e2.hi
       |trait Modules {
       |  type M1 = Int
       |}
       |/e2/free/Modules.scala
       |package e2.free
       |trait Modules {
       |  type M2 = Int
       |}
       |""".stripMargin,
    List(),
  )

  checkMembers(
    "direct-member-wins",
    """|/f/package.scala
       |package object f extends FAliases {
       |  type F1 = String
       |}
       |/f/FAliases.scala
       |package f
       |trait FAliases {
       |  type F1 = Int
       |  type F2 = Long
       |}
       |""".stripMargin,
    List("f/package.F1#", "f/package.F2#"),
  )

  checkMembers(
    "transitive-parent",
    """|/g/package.scala
       |package object g extends GA
       |/g/GA.scala
       |package g
       |trait GA extends GB {
       |  type G1 = Int
       |}
       |/g/GB.scala
       |package g
       |trait GB {
       |  type G2 = Long
       |}
       |""".stripMargin,
    List("g/package.G1#", "g/package.G2#"),
  )

  checkMembers(
    "parent-with-type-params",
    """|/h/package.scala
       |package object h extends HAliases[Int]
       |/h/HAliases.scala
       |package h
       |trait HAliases[T] {
       |  type H1 = List[T]
       |}
       |""".stripMargin,
    List("h/package.H1#"),
  )

  checkMembers(
    "external-parent-skipped",
    """|/i/package.scala
       |package object i extends Serializable with IAliases
       |/i/IAliases.scala
       |package i
       |trait IAliases {
       |  type I1 = Int
       |}
       |""".stripMargin,
    List("i/package.I1#"),
  )

  checkMembers(
    "plain-object-not-enriched",
    """|/k/syntax.scala
       |package k
       |object syntax extends KAliases
       |/k/KAliases.scala
       |package k
       |trait KAliases {
       |  type K1 = Int
       |}
       |""".stripMargin,
    List(),
  )

  checkMembers(
    "scala3-package-object",
    """|/j/package.scala
       |package object j extends JAliases
       |/j/JAliases.scala
       |package j
       |trait JAliases:
       |  type J1 = Int
       |""".stripMargin,
    List("j/package.J1#"),
    dialect = dialects.Scala3,
  )
}
