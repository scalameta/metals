package tests

import scala.concurrent.Future

import scala.meta.internal.metals.ServerCommands

class ReferenceLspSuite extends BaseRangesSuite("reference") {

  test("case-class") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
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
          |""".stripMargin,
        preInitialized = { () => server.didOpen("a/src/main/scala/a/A.scala") }
      )
      _ = assertNoDiagnostics()
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
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

  test("synthetic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
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
          |""".stripMargin,
        preInitialized = { () => server.didOpen("a/src/main/scala/a/A.scala") }
      )
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
        .map {
          case (chunk, i) =>
            s"""|/a/src/main/scala/a/Chunk$i.scala
                |package a
                |$chunk
                |""".stripMargin
        }
        .mkString("\n")
    check(s"$name-together", input(Seq((code +: moreCode) mkString "\n")))
    check(s"$name-split-up", input(code +: moreCode))
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
       |""".stripMargin
  )

  checkInSamePackage( // FIXME: doesn't find the case class declaration: https://github.com/scalameta/metals/issues/1553#issuecomment-617884934
    "simple-case-class-starting-elsewhere",
    """|case class Main(name: String) // doesn't find this
       |object F {
       |  val ma = <<Main>>("a")
       |}
       |""".stripMargin,
    """|object Other {
       |  val mb = Main.<<ap@@ply>>("b")
       |}
       |""".stripMargin
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
       |""".stripMargin
  )

  checkInSamePackage( // FIXME: should, but doesn't find the class declaration: https://github.com/scalameta/metals/issues/1553#issuecomment-617884934
    "case-class-unapply-starting-elsewhere",
    """|sealed trait Stuff
       |case class Foo(n: Int) extends Stuff // doesn't find this
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
       |""".stripMargin
  )

  checkInSamePackage( // FIXME: doesn't find the class declaration: https://github.com/scalameta/metals/issues/1553#issuecomment-617884934
    "explicit-unapply",
    """|sealed trait Stuff
       |class Foo(val n: Int) extends Stuff // doesn't find this; but should it?
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
       |""".stripMargin
  )

  test("edit-distance".flaky) {
    cleanWorkspace()
    for {
      _ <- server.initialize(
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
          |""".stripMargin,
        preInitialized = { () => server.didOpen("a/src/main/scala/a/A.scala") }
      )
      _ = assertNoDiagnostics()
      // Assert that goto definition and reference are still bijective after buffer changes
      // in both the definition source and reference sources.
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("a: Int", "\n")
      )
      _ <- server.didChange("b/src/main/scala/b/B.scala")(
        _.replace("val b", "\n  val number")
      )
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer.id)
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
      _ <- server.initialize(
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
      _ <- server.initialize(
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
       |trait <<A@@A>>
       |/a/src/main/scala/a/Other.scala
       |package a
       |trait BB extends <<AA>>
       |trait CC extends <<AA>>
       |trait DD extends <<AA>>
       |""".stripMargin
  )

  override def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String]
  ): Future[Unit] = {
    server.assertReferences(filename, edit, expected, base)
  }
}
