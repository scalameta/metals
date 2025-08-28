package tests

import scala.concurrent.Future

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration

class ReferenceLspSuite extends BaseRangesSuite("reference") {
  override def userConfig: UserConfiguration =
    UserConfiguration(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = true,
    )

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  test("case-class") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {"dependsOn": ["a"]}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |case class A(a: Int)
          |object A {
          |  def apply(a: Int, b: Int): A = A.apply(a) // overloaded non-synthetic apply
          |  def x: String = "a"
          |  def param(arg: Int): String =
          |    arg.toString
          |  def default = A
          |    .apply(a = 1)
          |    .copy(a = 2)
          |    .a
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |object B {
          |  val x: String = a.A.x
          |  val y: a.A = a.A.apply(1)
          |  val y2: a.A = a.A.apply(2, 3)
          |  val z: Int = y.a
          |  val param: String = a.A.param(arg = 2)
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ = server.assertReferenceDefinitionDiff(
        """|^
           |+a/src/main/scala/a/A.scala:4:36: a/A#
           |+  def apply(a: Int, b: Int): A = A.apply(a) // overloaded non-synthetic apply
           |+                                   ^^^^^
           |+a/src/main/scala/a/A.scala:9:6: a/A#
           |+    .apply(a = 1)
           |+     ^^^^^
           |+a/src/main/scala/a/A.scala:10:6: a/A#
           |+    .copy(a = 2)
           |+     ^^^^
           | b/src/main/scala/b/B.scala:4:12: a/A#
           |            ^
           |+b/src/main/scala/b/B.scala:4:20: a/A#
           |+  val y: a.A = a.A.apply(1)
           |+                   ^^^^^
           | b/src/main/scala/b/B.scala:5:13: a/A#
           |""".stripMargin
      )
    } yield ()
  }

  check(
    "references-standalone",
    """|/Main.scala
       |package a
       |
       |object Main{
       |  def <<hello>>() = println("Hello world")
       |  <<hello>>()
       |  <<hello>>()
       |  <<hello>>()
       |}
       |
       |""".stripMargin,
  )

  check(
    "companion-object",
    """|/Main.scala
       |case class <<RenderTile>>(
       |    lowBits: Int,
       |    highBits: Int,
       |    // 2 bits
       |    attrBits: Int
       |)
       |
       |object <<RenderTile>>{
       |  val zero = <<RenderTile>>(0, 0, 0)
       |}
       |""".stripMargin,
  )

  test("synthetic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  implicit def horror: String = "strings"
          |  def apply(a: Int)(implicit s: String) = Some(a)
          |}
          |/a/src/main/scala/a/B.scala
          |package a
          |import A.horror
          |object B {
          |  val a = A(1)
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = server.assertReferenceDefinitionDiff(
        """|
           |       ^^^^^
           |-a/src/main/scala/a/B.scala:4:11: a/A.apply().
           |-  val a = A(1)
           |-          ^
           | =================
           |          ^^^^^^
           |-a/src/main/scala/a/B.scala:4:11: a/A.horror().
           |-  val a = A(1)
           |-          ^^^^
           | ======
           |""".stripMargin
      )
      _ <- server.shutdown()
    } yield ()
  }

  /**
   * References should be found correctly regardless of the whether or not
   *  the declaring code and the refering share the same compilation unit.
   *
   *  This checks both cases by first placing them in the same file and then
   *  spread across different files. The package stays the same - this is
   *  actually necessary to test that references to synthetics can be found
   *  even in the absence of an explicit `import`, which is often the case when
   *  the referencing code shares with the declaring code the same package
   *  (but possibly a different compilation unit)
   */
  def checkInSamePackage(
      name: String,
      code: String,
      moreCode: String*
  ): Unit = {
    def input(chunks: Seq[String]): String =
      chunks.zipWithIndex
        .map { case (chunk, i) =>
          s"""|/a/src/main/scala/a/Chunk$i.scala
              |package a
              |$chunk
              |""".stripMargin
        }
        .mkString("\n")
    check(s"$name-together", input(Seq((code +: moreCode) mkString "\n")))
    check(s"$name-split-up".flaky, input(code +: moreCode))
  }

  checkInSamePackage(
    "simple-case-class",
    """|case class <<Ma@@in>>(name: String)
       |object F {
       |  val ma = <<Main>>("a")
       |}
       |""".stripMargin,
    """|object Other {
       |  val mb = <<Main>>.apply("b")
       |}
       |""".stripMargin,
  )

  checkInSamePackage(
    "simple-case-class-starting-elsewhere",
    """|case class Main(name: String)
       |object F {
       |  val ma = <<Main>>("a")
       |}
       |""".stripMargin,
    """|object Other {
       |  val mb = Main.<<ap@@ply>>("b")
       |}
       |""".stripMargin,
  )

  checkInSamePackage(
    "case-class-unapply",
    """|sealed trait Stuff
       |case class <<Fo@@o>>(n: Int) extends Stuff
       |""".stripMargin,
    """|object Main {
       |   def n(stuff: Stuff): Option[Int] = stuff match {
       |     case <<Foo>>(n) => Some(n)
       |     case _ => None
       |   }
       |}
       |""".stripMargin,
  )

  checkInSamePackage( // FIXME: should, but doesn't find the class declaration: https://github.com/scalameta/metals/issues/1553#issuecomment-617884934
    "case-class-unapply-starting-elsewhere",
    """|sealed trait Stuff
       |case class <<Foo>>(n: Int) extends Stuff // doesn't find this
       |""".stripMargin,
    """|
       |object ByTheWay {
       |  val <<Foo>>(one) =
       |      <<Foo>>(1)
       |}
       |""".stripMargin,
    """|object Main {
       |   def n(stuff: Stuff): Option[Int] = stuff match {
       |     case <<Fo@@o>>(n) => Some(n)
       |     case _ => None
       |   }
       |}
       |""".stripMargin,
  )

  checkInSamePackage(
    "explicit-unapply",
    """|sealed trait Stuff
       |class Foo(val n: Int) extends Stuff
       |object Foo {
       |  def apply(n: Int): Foo = new Foo(n)
       |  def <<un@@apply>>(foo: Foo): Option[Int] = Some(foo.n)
       |}
       |""".stripMargin,
    """|object Main {
       |   def n(stuff: Stuff): Option[Int] = stuff match {
       |     case <<Foo>>(n) => Some(n)
       |     case _ => None
       |   }
       |}
       |""".stripMargin,
  )

  test("edit-distance".flaky) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {"dependsOn": ["a"]}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  def bar(a: Int): Int = a
          |  def foo = bar(1)
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |object B {
          |  val b = new a.A().bar(2)
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      // Assert that goto definition and reference are still bijective after buffer changes
      // in both the definition source and reference sources.
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("a: Int", "\n")
      )
      _ <- server.didChange("b/src/main/scala/b/B.scala")(
        _.replace("val b", "\n  val number")
      )
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
      _ = server.assertReferenceDefinitionDiff(
        """|        ^
           |+=============
           |+= b/B.number.
           |+=============
           |+b/src/main/scala/b/B.scala:4:7: b/B.number.
           |+  val number = new a.A().bar(2)
           |+      ^^^^^^
           |""".stripMargin
      )
    } yield ()
  }

  test("var") {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  var a = 1
          |  a = 2
          |  A.a = 2
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ = server.assertReferenceDefinitionBijection()
    } yield ()
  }

  test("implicit") {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |trait Document
          |
          |class DD extends Document
          |
          |trait Hello[T <: Document]{
          |
          |  implicit class Better[T <: Document](doc : T){
          |    def other() = {}
          |  }
          |}
          |
          |class Hey extends Hello[DD]{
          |  Some(new DD).map(_.other())
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ = server.assertReferenceDefinitionBijection()
    } yield ()
  }

  check(
    "ordering",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait <<AA>>
       |/a/src/main/scala/a/Other.scala
       |package a
       |trait BB extends <<AA>>
       |trait CC extends <<AA>>
       |trait DD extends <<AA>>
       |""".stripMargin,
  )

  check(
    "worksheet",
    """|/a/src/main/scala/a/Main.worksheet.sc
       |trait <<AA>>
       |trait BB extends <<AA>>
       |trait CC extends <<AA>>
       |trait DD extends <<AA>>
       |""".stripMargin,
  )

  check(
    "case-class-separate-reference",
    """|/a/src/main/scala/a/Main.scala
       |case class <<Main>>(name: String)
       |object F {
       |  val ma = <<Main>>("a")
       |}
       |/a/src/main/scala/a/Other.scala
       |object Other {
       |  val mb = new <<Main>>("b")
       |}
       |""".stripMargin,
  )

  check(
    "synthetic-object-reference",
    """|/a/src/main/scala/a/Main.scala
       |case class <<Main>>(name: String)
       |object F {
       |  val ma = <<Main>>("a")
       |}
       |/a/src/main/scala/a/Other.scala
       |object Other {
       |  val mb = new <<Main>>("b")
       |}
       |""".stripMargin,
  )

  check(
    "constructor",
    """|/a/src/main/scala/a/Main.scala
       |case class Name(<<value>>: String)
       |
       |object Main {
       |  val name2 = new Name(<<value>> = "44")
       |}
       |""".stripMargin,
  )

  test("i6101") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": { "libraryDependencies": ["com.lihaoyi::sourcecode:0.1.7"] },
          |  "b": { "libraryDependencies": ["com.lihaoyi::sourcecode:0.1.7"] }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |import sourcecode.Line
          |
          |object A {
          |  def line = Line
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |
          |//additional line for unambiguous sorting
          |import sourcecode.Line
          |
          |object B {
          |  def line = Line
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      references <- server.references("a/src/main/scala/a/A.scala", "Line")
      _ = assertNoDiff(
        references,
        """|a/src/main/scala/a/A.scala:3:19: info: reference
           |import sourcecode.Line
           |                  ^^^^
           |b/src/main/scala/b/B.scala:4:19: info: reference
           |import sourcecode.Line
           |                  ^^^^
           |a/src/main/scala/a/A.scala:6:14: info: reference
           |  def line = Line
           |             ^^^^
           |b/src/main/scala/b/B.scala:7:14: info: reference
           |  def line = Line
           |             ^^^^
           |""".stripMargin,
      )
      _ <- server.shutdown()
    } yield ()
  }

  test("i6348") {
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "moduleA": { },
           |  "moduleB": { },
           |  "root": { dependsOn: [ "moduleA", "moduleB" ] }
           |}
           |/moduleA/src/main/scala/Person.scala
           |package a
           |object Person{
           |  def fromList(list: List[Any])={
           |    println(list)
           |  }
           |}
           |/moduleB/src/main/scala/Main.scala
           |object A {
           |}
           |/root/src/main/scala/Main.scala
           |
           |""".stripMargin
      )
      _ <- server.didOpen("root/src/main/scala/Main.scala")
      _ <- server.didOpen("moduleB/src/main/scala/Main.scala")
      _ <- server.didChange("moduleB/src/main/scala/Main.scala")(_ =>
        """|object A {
           |  val i: String = 1
           |}
           |""".stripMargin
      )
      _ <- server.didSave("moduleB/src/main/scala/Main.scala")
      _ <- server.didOpen("root/src/main/scala/Main.scala")
      _ <- server.didChange("root/src/main/scala/Main.scala")(_ =>
        """|import a.Person
           |object Main {
           |  val i = Person.fromList(Nil)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("moduleA/src/main/scala/Person.scala")
      references <- server.references(
        "moduleA/src/main/scala/Person.scala",
        "fromList",
      )
      _ = assertNoDiff(
        references,
        """|moduleA/src/main/scala/Person.scala:3:7: info: reference
           |  def fromList(list: List[Any])={
           |      ^^^^^^^^
           |root/src/main/scala/Main.scala:3:18: info: reference
           |  val i = Person.fromList(Nil)
           |                 ^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.shutdown()
    } yield ()
  }

  test("local-3.4.x") {
    for {
      _ <- initialize("""
                        |/metals.json
                        |{
                        |  "a": { "scalaVersion": "3.4.2" }
                        |}
                        |/a/src/main/scala/a/A.scala
                        |package a
                        |
                        |object A {
                        |  def foo = {
                        |    val bar = 1
                        |    val g = bar
                        |    bar
                        |  }
                        |}
                        |""".stripMargin)
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      references <- server.references("a/src/main/scala/a/A.scala", "bar")
      _ = assertNoDiff(
        references,
        """|a/src/main/scala/a/A.scala:5:9: info: reference
           |    val bar = 1
           |        ^^^
           |a/src/main/scala/a/A.scala:6:13: info: reference
           |    val g = bar
           |            ^^^
           |a/src/main/scala/a/A.scala:7:5: info: reference
           |    bar
           |    ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  override def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String],
  ): Future[Unit] = {
    server.assertReferences(filename, edit, expected, base)
  }
}
