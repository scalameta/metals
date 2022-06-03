package tests

import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.{BuildInfo => V}

class HoverLspSuite extends BaseLspSuite("hover-") with TestHovers {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  test("basic".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
        """.stripMargin
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|```scala
           |def head: Int
           |```
           |Selects the first element of this iterable collection.
           | Note: might return different results for different runs, unless the underlying collection type is ordered.
           |
           |**Returns:** the first element of this iterable collection.
           |
           |**Throws**
           |- `NoSuchElementException`: if the iterable collection is empty.
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("basic-rambo".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":{"scalaVersion" : ${V.scala3}}}
            |/Main.scala
            |object Main extends App {
            |  // @@
            |}
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|```scala
           |def head: Int
           |```
           |Selects the first element of this iterable collection.
           | Note: might return different results for different runs, unless the underlying collection type is ordered.
           |
           |**Returns:** the first element of this iterable collection.
           |
           |**Throws**
           |- `NoSuchElementException`: if the iterable collection is empty.
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("docstrings".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /**
          |    * test
          |    */
          |  def foo(x: Int): Int = ???
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s => s) // index docs
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|```scala
           |def foo(x: Int): Int
           |```
           |test
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("update-docstrings".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /**
          |    * test
          |    */
          |  def foo(x: Int): Int = ???
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s => s)
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|```scala
           |def foo(x: Int): Int
           |```
           |test
           |""".stripMargin.hover,
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s =>
        s.replace("test", "test2")
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|```scala
           |def foo(x: Int): Int
           |```
           |test2
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("docstrings java parentdoc".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  public static class Def {
          |    /**
          |      * test docs
          |      */
          |    public void foo(int x) {}
          |  }
          |
          |  public static class ChildDef extends Def {
          |    @Override
          |    public void foo(int x) {}
          |  }
          |  void test() {
          |    new ChildDef().foo(1);
          |  }
          |}
        """.stripMargin
      )
      _ <- server.assertHover(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  public static class Def {
          |    /**
          |      * test docs
          |      */
          |    public void foo(int x) {}
          |  }
          |
          |  public static class ChildDef extends Def {
          |    @Override
          |    public void foo(int x) {}
          |  }
          |  void test() {
          |    new Chil@@dDef().foo(1);
          |  }
          |}""".stripMargin,
        """```java
          |public ChildDef()
          |```
          |""".stripMargin.hover,
      )
      _ <- server.assertHover(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  public static class Def {
          |    /**
          |      * test docs
          |      */
          |    public void foo(int x) {}
          |  }
          |
          |  public static class ChildDef extends Def {
          |    @Override
          |    public void foo(int x) {}
          |  }
          |  void test() {
          |    new ChildDef().fo@@o(1);
          |  }
          |}""".stripMargin,
        """```java
          |public void foo(int x)
          |```
          |test docs
          |""".stripMargin.hover,
      )
    } yield ()
  }

  test("dependencies".tag(FlakyWindows), withoutVirtualDocs = true) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |  println(42)
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ =
        server.workspaceDefinitions // triggers goto definition, creating Predef.scala
      _ <- server.assertHover(
        "scala/Predef.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|```scala
           |def head: Int
           |```
           |Selects the first element of this iterable collection.
           | Note: might return different results for different runs, unless the underlying collection type is ordered.
           |
           |**Returns:** the first element of this iterable collection.
           |
           |**Throws**
           |- `NoSuchElementException`: if the iterable collection is empty.
           |""".stripMargin.hover,
        root = workspace.resolve(Directories.readonly),
      )
    } yield ()
  }

}
