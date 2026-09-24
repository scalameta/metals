package tests.pc

import coursierapi.Dependency
import tests.BaseAutoImportsSuite

/**
 * Auto-imports for symbols that are importable only through a package object,
 * either declared in it or inherited from its mixin parents (issue #2583).
 *
 * doobie exposes its public API this way: `package object doobie extends
 * Aliases ...` where `trait Aliases extends Types with Modules`, `Types`
 * declares `type Transactor[M[_]] = doobie.util.transactor.Transactor[M]`
 * and `Modules` declares `val Transactor = doobie.util.transactor.Transactor`
 * — so the members are inherited transitively and exist in both namespaces.
 */
class AutoImportsPackageObjectSuite extends BaseAutoImportsSuite {
  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    Seq(
      Dependency.of("org.tpolecat", s"doobie-core_$binaryVersion", "0.13.4"),
      // declares `class BlackWhiteContext` directly inside `package object
      // pprint`, which is compiled to a `package$BlackWhiteContext` classfile
      Dependency.of("com.lihaoyi", s"pprint_$binaryVersion", "0.9.6")
    )
  }

  check(
    "alias-through-mixin",
    """|object A {
       |  def xa: <<Transactor>>[List] = ???
       |}
       |""".stripMargin,
    """|doobie
       |doobie.util.transactor
       |""".stripMargin
  )

  checkEdit(
    "alias-through-mixin-edit",
    """|object A {
       |  def xa: <<Transactor>>[List] = ???
       |}
       |""".stripMargin,
    """|import doobie.Transactor
       |object A {
       |  def xa: Transactor[List] = ???
       |}
       |""".stripMargin,
    selection = 0
  )

  check(
    "term-through-mixin",
    """|object A {
       |  val xa = <<Transactor>>.fromDriverManager
       |}
       |""".stripMargin,
    """|doobie
       |doobie.util.transactor
       |""".stripMargin
  )

  checkEdit(
    "term-through-mixin-edit",
    """|object A {
       |  val xa = <<Transactor>>.fromDriverManager
       |}
       |""".stripMargin,
    """|import doobie.Transactor
       |object A {
       |  val xa = Transactor.fromDriverManager
       |}
       |""".stripMargin,
    selection = 0
  )

  // the type alias and the val re-export are distinct symbols that render
  // the same import, so they collapse into one `doobie` code action
  check(
    "type-term-twin-dedup",
    """|object A {
       |  val xa = <<Transactor>>
       |}
       |""".stripMargin,
    """|doobie
       |doobie.util.transactor
       |""".stripMargin
  )

  checkEdit(
    "in-import-tree",
    """|import <<Transactor>>
       |object A
       |""".stripMargin,
    """|import doobie.Transactor
       |object A
       |""".stripMargin,
    selection = 0
  )

  // `type Eq`/`val Eq` are declared directly in `package object cats` (on the
  // classpath through doobie); direct declarations have no classfile either,
  // so the probe must offer them too
  check(
    "direct-member",
    """|object A {
       |  def eq: <<Eq>>[Int] = ???
       |}
       |""".stripMargin,
    """|cats
       |cats.kernel
       |""".stripMargin
  )

  checkEdit(
    "direct-member-edit",
    """|object A {
       |  val e = <<Eq>>.fromUniversalEquals[Int]
       |}
       |""".stripMargin,
    """|import cats.Eq
       |object A {
       |  val e = Eq.fromUniversalEquals[Int]
       |}
       |""".stripMargin,
    selection = 0
  )

  // `ConnectionIO` is a type-only alias declared in `doobie.free.Types` and
  // inherited into the `doobie`, `doobie.free` and `doobie.hi` package
  // objects: one import is offered per package object exposing it, and none
  // in a term position where importing a type cannot fix the error
  check(
    "type-only-alias-multiple-package-objects",
    """|object A {
       |  def io: <<ConnectionIO>>[Int] = ???
       |}
       |""".stripMargin,
    """|doobie
       |doobie.free
       |doobie.hi
       |""".stripMargin
  )

  // A candidate whose namespace cannot fix the error is still offered, the
  // same way an ordinary type-only class is offered in a term position (e.g.
  // `val x = AbstractSet`). `correctInTreeContext` is the general filter for
  // this and covers neither position below; improving it for all symbols is
  // left as a follow-up rather than special-casing package objects here.
  check(
    "type-only-alias-in-term-position",
    """|object A {
       |  val io = <<ConnectionIO>>.apply
       |}
       |""".stripMargin,
    """|doobie
       |doobie.free
       |doobie.hi
       |""".stripMargin
  )

  check(
    "type-only-alias-in-bare-expression",
    """|object A {
       |  val io = <<ConnectionIO>>
       |}
       |""".stripMargin,
    """|doobie
       |doobie.free
       |doobie.hi
       |""".stripMargin
  )

  // `FC` is a term-only module alias declared in `doobie.free.Modules` and
  // inherited into the `doobie`, `doobie.free` and `doobie.hi` package
  // objects
  check(
    "term-only-val-multiple-package-objects",
    """|object A {
       |  val fc = <<FC>>.commit
       |}
       |""".stripMargin,
    """|doobie
       |doobie.free
       |doobie.hi
       |""".stripMargin
  )

  // Unlike the two cases above, an ordinary `object` in a type position is
  // already dropped by `correctInTreeContext`, which asks whether the symbol
  // is a module with a companion class. `FC` is a plain `val`, so it is not a
  // module and slips through that check. Tightening the check is not enough
  // on its own either, because it only expresses a preference: when every
  // candidate fails it, all of them are offered anyway.
  check(
    "term-only-val-in-type-position",
    """|object A {
       |  def fc: <<FC>> = ???
       |}
       |""".stripMargin,
    """|doobie
       |doobie.free
       |doobie.hi
       |""".stripMargin
  )

  // A class declared inside a package object has a classfile of its own
  // (`package$BlackWhiteContext.class`), so the classfile search finds it too
  // and its owner is the package object. Both routes must render the same
  // package, otherwise the user is offered `pprint` and `pprint.package`
  // twice and the diagnostic drops out of "import all missing symbols".
  check(
    "class-declared-in-package-object-offered-once",
    """|object A {
       |  val c: <<BlackWhiteContext>> = ???
       |}
       |""".stripMargin,
    """|pprint
       |""".stripMargin
  )
}
