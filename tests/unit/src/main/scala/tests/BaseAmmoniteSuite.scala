package tests

abstract class BaseAmmoniteSuite(scalaVersion: String)
    extends BaseLspSuite("ammonite") {

  override def munitIgnore: Boolean = !isValidScalaVersionForEnv(scalaVersion)

  test("simple script") {
    // single script with import $ivy-s
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala 2.12.10, ammonite 2.0.5-SNAPSHOT
           |import $$ivy.`io.circe::circe-core:0.12.3`
           |import $$ivy.`io.circe::circe-generic:0.12.3`
           |import $$ivy.`io.circe::circe-parser:0.12.3`
           |import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
           |
           |sealed trait Foo
           |case class Bar(xs: Vector[String]) extends Foo
           |case class Qux(i: Int, d: Option[Double]) extends Foo
           |
           |val foo: Foo = Qux(13, Some(14.0))
           |
           |val json = foo.asJson.noSpaces
           |
           |val decodedFoo = decode[Foo](json)
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand("ammonite-start")

      // asJson
      // via Ammonite-generated Semantic DB
      _ <- server.assertDefinitionAtLocation(
        "main.sc",
        12,
        18,
        ".metals/readonly/io/circe/syntax/package.scala"
      )

      // noSpaces
      // via Ammonite-generated Semantic DB
      _ <- server.assertDefinitionAtLocation(
        "main.sc",
        12,
        27,
        ".metals/readonly/io/circe/Json.scala"
      )

      // Second 'noSpaces' in
      //   final def noSpaces: String = Printer.noSpaces.print(this)
      // via presentation compiler, using the Ammonite build target classpath
      _ <- server.assertDefinitionAtLocation(
        ".metals/readonly/io/circe/Json.scala",
        122,
        45,
        ".metals/readonly/io/circe/Printer.scala"
      )

      // Foo
      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- server.assertDefinitionAtLocation("main.sc", 14, 25, "main.sc", 6)

    } yield ()
  }

  test("simple errored script") {
    val expectedDiagnostics =
      """main.sc:15:25: error: not found: type Fooz
        |val decodedFoo = decode[Fooz](json)
        |                        ^^^^
        |""".stripMargin
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/main.sc
           | // scala 2.12.10, ammonite 2.0.5-SNAPSHOT
           |import $$ivy.`io.circe::circe-core:0.12.3`
           |import $$ivy.`io.circe::circe-generic:0.12.3`
           |import $$ivy.`io.circe::circe-parser:0.12.3`
           |import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
           |
           |sealed trait Foo
           |case class Bar(xs: Vector[String]) extends Foo
           |case class Qux(i: Int, d: Option[Double]) extends Foo
           |
           |val foo: Foo = Qux(13, Some(14.0))
           |
           |val json = foo.asJson.noSpaces
           |
           |val decodedFoo = decode[Fooz](json)
           |
           |// no val, column of diagnostics must be correct too
           |decode[Foozz](json)
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand("ammonite-start")

      _ = server.expectDiagnostics("main.sc", expectedDiagnostics)

    } yield ()
  }

  test("multi script") {
    // multiple scripts with mixed import $file-s and $ivy-s
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion"
           |  }
           |}
           |/lib1.sc
           |trait HasFoo {
           |  def foo: String = "foo"
           |}
           |
           |/lib2.sc
           |import $$ivy.`io.circe::circe-core:0.12.3`
           |import $$ivy.`io.circe::circe-generic:0.12.3`
           |import $$ivy.`io.circe::circe-parser:0.12.3`
           |import $$file.lib1, lib1.HasFoo
           |
           |trait HasReallyFoo extends HasFoo
           |
           |/main.sc
           | // scala 2.12.10, ammonite 2.0.5-SNAPSHOT
           |import $$file.lib2, lib2.HasReallyFoo
           |import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
           |
           |sealed trait Foo extends HasReallyFoo
           |case class Bar(xs: Vector[String]) extends Foo
           |case class Qux(i: Int, d: Option[Double]) extends Foo
           |
           |val foo: Foo = Qux(13, Some(14.0))
           |
           |val json = foo.asJson.noSpaces
           |
           |val decodedFoo = decode[Foo](json)
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand("ammonite-start")

      // asJson
      // via Ammonite-generated Semantic DB
      _ <- server.assertDefinitionAtLocation(
        "main.sc",
        10,
        18,
        ".metals/readonly/io/circe/syntax/package.scala"
      )

      // noSpaces
      // via Ammonite-generated Semantic DB
      _ <- server.assertDefinitionAtLocation(
        "main.sc",
        10,
        27,
        ".metals/readonly/io/circe/Json.scala"
      )

      // Second 'noSpaces' in
      //   final def noSpaces: String = Printer.noSpaces.print(this)
      // via presentation compiler, using the Ammonite build target classpath
      _ <- server.assertDefinitionAtLocation(
        ".metals/readonly/io/circe/Json.scala",
        122,
        45,
        ".metals/readonly/io/circe/Printer.scala"
      )

      // Foo
      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- server.assertDefinitionAtLocation("main.sc", 12, 25, "main.sc", 4)

      // HasReallyFoo
      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- server.assertDefinitionAtLocation("main.sc", 4, 31, "lib2.sc")

      // HasFoo
      // via Ammonite-generated Semantic DB and indexing of Ammonite-generated source of $file dependencies
      _ <- server.assertDefinitionAtLocation("lib2.sc", 5, 30, "lib1.sc")

    } yield ()
  }

}
