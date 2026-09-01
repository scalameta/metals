package tests

import java.net.URLDecoder

import scala.concurrent.Future

import scala.meta.internal.docstrings.MetalsSymbolLink
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.CommandHTMLFormat
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScaladocLinkParams
import scala.meta.internal.metals.ServerCommands
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

  test("basic - Scala 3.5.0".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{"scalaVersion":"3.5.0"}}
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

  test("dependency", withoutVirtualDocs = true) {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${V.scala213}",
           |    "libraryDependencies" : [ "org.scala-lang:scala-reflect:${V.scala213}"] 
           |  }
           |}
           |/a/src/main/scala/Main.scala
           |object Main {
           |  import scala.reflect.internal.Scopes
           |}
           |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      /* Scopes.scala from scala-reflect has unsupported Scala 3 syntax
       * For Scala 2.13.x it should not show any diagnostics.
       *
       * workspaceDefinitions will open Scopes.scala and trigger diagnostics.
       */
      _ = server.workspaceDefinitions
      _ <- server.didOpen("scala/reflect/internal/Scopes.scala")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("basic-rambo".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":{"scalaVersion" : ${V.scala213}}}
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
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
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

  // When the client doesn't support command links, scaladoc wiki links such as
  // `[[a.Bar]]` must be rendered as plain text rather than a broken markdown
  // link with an unresolvable target (scalameta/metals#3383).
  test("wiki-link-plaintext-fallback".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |class Bar
          |object Def {
          |  /**
          |    * See [[a.Bar]] for details.
          |    */
          |  def foo(x: Int): Int = ???
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
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
           |See a.Bar for details.
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
      _ <- server.didSave("a/src/main/scala/a/Def.scala")
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
      _ <- server.didChange("a/src/main/scala/a/Def.scala")(s =>
        s.replace("test", "test2")
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")
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

  test("backticked-name".tag(FlakyWindows)) {
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
          |  def `foo ba@@r baz` = 123
          |}""".stripMargin,
        """|```scala
           |def `foo bar baz`: Int
           |```
           |""".stripMargin.hover,
      )
    } yield ()
  }

}

class HoverWikiLinkLspSuite
    extends BaseLspSuite("hover-wiki-link-")
    with TestHovers {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      TestingServer.TestDefault.copy(
        commandInHtmlFormat = Some(CommandHTMLFormat.VSCode)
      )
    )

  private val docWithWikiLinks =
    """/metals.json
      |{"a":{}}
      |/a/src/main/scala/a/Def.scala
      |package a
      |object Bar {
      |  def baz(x: Int): Int = x
      |}
      |object Def {
      |  /**
      |    * See [[a.Bar]] and [[a.Bar.baz]].
      |    */
      |  def foo(x: Int): Int = ???
      |}
      |/a/src/main/scala/a/Main.scala
      |package a
      |object Main {
      |}
    """.stripMargin

  private val linkCommand =
    raw"command:metals\.goto-scaladoc-link\?([^)]+)".r

  /**
   * Hovers, then "clicks" the first rendered scaladoc-link command and returns
   * the uri it navigated to (if any). Resolution happens lazily in the command
   * handler, so hovering itself does no symbol resolution (scalameta/metals#3383).
   */
  private def clickFirstLink(
      filename: String,
      query: String,
  ): Future[Option[String]] = {
    // `clientCommands` accumulates across clicks, so clear it first to read the
    // location produced by *this* click rather than an earlier one.
    client.clientCommands.clear()
    for {
      hover <- server.hover(filename, query, workspace)
      params = linkCommand.findFirstMatchIn(hover).map { m =>
        val json = URLDecoder.decode(m.group(1), "UTF-8")
        json.parseJson.getAsJsonArray().get(0).as[ScaladocLinkParams].get
      }
      _ <- params.fold(Future.unit)(p =>
        server.executeCommand(ServerCommands.GotoScaladocLink, p).map(_ => ())
      )
    } yield client.clientCommands.asScala.collectFirst {
      case ClientCommands.GotoLocation(location) => location.uri
    }
  }

  // When the client supports command links, a scaladoc wiki link (both a type
  // and a method link) is rewritten into a clickable command link that resolves
  // lazily on click (scalameta/metals#3383).
  test("wiki-link-command".tag(FlakyWindows)) {
    for {
      _ <- initialize(docWithWikiLinks)
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      hover <- server.hover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        workspace,
      )
    } yield {
      assert(
        hover.contains("[a.Bar](command:metals.goto-scaladoc-link"),
        hover,
      )
      assert(
        hover.contains("[a.Bar.baz](command:metals.goto-scaladoc-link"),
        hover,
      )
      assert(!hover.contains(MetalsSymbolLink.scheme), hover)
    }
  }

  // The marker must not leak to other docstring surfaces: completion item
  // documentation strips it to plain text (scalameta/metals#3383).
  test("wiki-link-completion-strip".tag(FlakyWindows)) {
    for {
      _ <- initialize(docWithWikiLinks)
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didChange("a/src/main/scala/a/Main.scala")(_ =>
        "package a\nobject Main {\n  Def.fo\n}\n"
      )
      items <- server.completionList(
        "a/src/main/scala/a/Main.scala",
        "Def.fo@@",
      )
      foo = items.getItems.asScala.find(_.getLabel().startsWith("foo")).get
      resolved <- server.completionItemResolve(foo)
    } yield {
      val doc = Option(resolved.getDocumentation()) match {
        case Some(d) if d.isRight() => d.getRight().getValue()
        case Some(d) if d.isLeft() => d.getLeft()
        case _ => ""
      }
      assert(doc.contains("a.Bar"), doc)
      assert(!doc.contains(MetalsSymbolLink.scheme), doc)
      assert(!doc.contains("command:"), doc)
    }
  }

  // A title that itself contains brackets (e.g. a type like `Bar[X]`) must still
  // be rewritten into a command link and never leak. The label's brackets are
  // escaped so the marker stays parseable (scalameta/metals#3383).
  test("wiki-link-bracket-title".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Bar
          |object Def {
          |  /**
          |    * See [[a.Bar Bar[X]ref]].
          |    */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      hover <- server.hover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        workspace,
      )
    } yield {
      assert(
        hover.contains("""[Bar\[X\]ref](command:metals.goto-scaladoc-link"""),
        hover,
      )
      assert(!hover.contains(MetalsSymbolLink.scheme), hover)
    }
  }

  // Scaladoc's `[[[ ]]]` syntax permits a title with an unmatched bracket; the
  // marker must still be rewritten and never leak (scalameta/metals#3383).
  test("wiki-link-unmatched-bracket-title".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Bar
          |object Def {
          |  /**
          |    * See [[[a.Bar title ] suffix]]].
          |    */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      hover <- server.hover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        workspace,
      )
    } yield {
      assert(hover.contains("command:metals.goto-scaladoc-link"), hover)
      assert(!hover.contains(MetalsSymbolLink.scheme), hover)
    }
  }

  // Clicking an absolute link navigates to the symbol's definition.
  test("wiki-link-navigate-absolute".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Bar.scala
          |package a
          |object Bar
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[a.Bar]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Bar.scala")), uri.toString)
  }

  // Clicking a relative link resolves it against the docstring owner's context.
  test("wiki-link-navigate-relative".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[helper]]. */
          |  def foo(x: Int): Int = ???
          |  def helper: Int = 0
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Def.scala")), uri.toString)
  }

  // A relative link is qualified at index time with the defining source's
  // imports, so `[[Target]]` after `import a.b.Target` navigates to the imported
  // type even though it lives in another package (scalameta/metals#3383).
  test("wiki-link-navigate-scala-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b.Target
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Target.scala")), uri.toString)
  }

  // The same index-time qualification applies to Java imports, so a Javadoc
  // `{@link Helper}` after `import a.b.Helper;` navigates to the imported class
  // (scalameta/metals#3383).
  test("wiki-link-navigate-java-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/b/Helper.java
          |package a.b;
          |public class Helper {}
          |/a/src/main/java/a/Foo.java
          |package a;
          |import a.b.Helper;
          |public class Foo {
          |  /** See {@link Helper}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |import a.b.Helper;
          |public class Foo {
          |  /** See {@link Helper}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Helper.java")), uri.toString)
  }

  // A local definition shadows an imported name: the import is only a fallback,
  // so `[[Target]]` resolves to the object's own `Target`, not the imported one
  // (scalameta/metals#3383).
  test("wiki-link-navigate-shadows-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b.Target
          |object Def {
          |  class Target
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
      // The local `Def.Target` lives in Def.scala, the import in Target.scala.
    } yield assert(uri.exists(_.endsWith("/a/Def.scala")), uri.toString)
  }

  // Imports are lexically scoped: two sibling objects importing a different
  // `Target` each resolve to their own import, not whichever was last seen in
  // the file (scalameta/metals#3383).
  test("wiki-link-navigate-lexical-import-scope".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/c/Target.scala
          |package a.c
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |object One {
          |  import a.b.Target
          |  /** See [[Target]]. */
          |  def fa(x: Int): Int = ???
          |}
          |object Two {
          |  import a.c.Target
          |  /** See [[Target]]. */
          |  def fb(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      fromOne <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = One.f@@a(1)
          |}""".stripMargin,
      )
      fromTwo <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Two.f@@b(1)
          |}""".stripMargin,
      )
    } yield {
      assert(fromOne.exists(_.endsWith("/a/b/Target.scala")), fromOne.toString)
      assert(fromTwo.exists(_.endsWith("/a/c/Target.scala")), fromTwo.toString)
    }
  }

  // A nearer import shadows a farther one of the same name: the inner object's
  // `import a.c.Target` wins over the outer `import a.b.Target`, so `[[Target]]`
  // in the inner scope resolves to `a.c.Target` (scalameta/metals#3383).
  test("wiki-link-navigate-nested-import-shadowing".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/c/Target.scala
          |package a.c
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b.Target
          |object Outer {
          |  import a.c.Target
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Outer.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/c/Target.scala")), uri.toString)
  }

  // A Java wildcard import (`import a.b.*;`) qualifies a relative Javadoc link
  // via its prefix, so `{@link Helper}` resolves to `a.b.Helper`
  // (scalameta/metals#3383).
  // A docstring whose link label itself contains the marker scheme (an escaped
  // `](metals-wiki-link2:…)`) must not crash hover/completion rendering — it
  // degrades gracefully instead (scalameta/metals#3383).
  test("wiki-link-scheme-in-label".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Bar
          |object Def {
          |  /** See [[a.Bar click ](metals-wiki-link2:x) here]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      hover <- server.hover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        workspace,
      )
    } yield assert(hover.contains("here"), hover)
  }

  // A type-forced bare link (`[[Future!]]`) qualifies via the wildcard/implicit
  // scope just like `[[Future]]`, so the `!` suffix doesn't suppress its import
  // fallbacks (scalameta/metals#3383).
  test("wiki-link-type-forced-wildcard".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import scala.concurrent._
          |object Def {
          |  /** See [[Future!]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(
      uri.exists(_.endsWith("concurrent/Future.scala")),
      uri.toString,
    )
  }

  // Two single-static imports of the same name are ambiguous (a Java compile
  // error), so the link does not navigate to whichever was imported last
  // (scalameta/metals#3383).
  test("wiki-link-java-duplicate-static-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/b/A.java
          |package a.b;
          |public class A { public static int max(int x) { return x; } }
          |/a/src/main/java/a/c/B.java
          |package a.c;
          |public class B { public static int max(int x) { return x; } }
          |/a/src/main/java/a/Foo.java
          |package a;
          |import static a.b.A.max;
          |import static a.c.B.max;
          |public class Foo {
          |  /** See {@link max}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |import static a.b.A.max;
          |import static a.c.B.max;
          |public class Foo {
          |  /** See {@link max}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield {
      assert(uri.isEmpty, uri.toString)
      assert(
        client.showMessages.asScala.exists(
          _.getMessage().contains("Could not uniquely resolve")
        ),
        client.showMessages.toString,
      )
    }
  }

  test("wiki-link-navigate-java-wildcard-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/b/Helper.java
          |package a.b;
          |public class Helper {}
          |/a/src/main/java/a/Foo.java
          |package a;
          |import a.b.*;
          |public class Foo {
          |  /** See {@link Helper}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |import a.b.*;
          |public class Foo {
          |  /** See {@link Helper}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Helper.java")), uri.toString)
  }

  // A wildcard import (`import a.b._`) qualifies a relative link via its prefix,
  // so `[[Target]]` resolves to `a.b.Target` (scalameta/metals#3383).
  test("wiki-link-navigate-wildcard-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b._
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Target.scala")), uri.toString)
  }

  // The leading name of a member/nested link is qualified by imports too, so
  // `[[Outer.Inner]]` after `import a.b.Outer` resolves to the nested type even
  // though the target is dotted (scalameta/metals#3383).
  test("wiki-link-navigate-member-on-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Outer.scala
          |package a.b
          |object Outer {
          |  class Inner
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b.Outer
          |object Def {
          |  /** See [[Outer.Inner]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Outer.scala")), uri.toString)
  }

  // Two wildcard imports binding the same name to different definitions are
  // ambiguous, so the link does not navigate rather than picking one
  // arbitrarily (scalameta/metals#3383).
  test("wiki-link-ambiguous-wildcard-no-navigation".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/c/Target.scala
          |package a.c
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b._
          |import a.c._
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.isEmpty, uri.toString)
  }

  // A member access through a lowercase imported object resolves: `[[util.Tool]]`
  // after `import a.b.util` navigates to `a.b.util.Tool`, even though `util` is
  // not a capitalized type (scalameta/metals#3383).
  test("wiki-link-navigate-lowercase-object-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/util.scala
          |package a.b
          |object util {
          |  class Tool
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b.util
          |object Def {
          |  /** See [[util.Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/util.scala")), uri.toString)
  }

  // A member access through a lowercase object brought in by a wildcard import
  // resolves: after `import p._`, `[[util.Tool]]` navigates to `p.util.Tool`
  // even though `util` is a lowercase object (scalameta/metals#3383).
  test("wiki-link-navigate-wildcard-lowercase-object".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/p/util.scala
          |package p
          |object util {
          |  class Tool
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import p._
          |object Def {
          |  /** See [[util.Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/p/util.scala")), uri.toString)
  }

  // A nearer wildcard import shadows a farther one: the inner `import a.c._`
  // wins over the outer `import a.b._`, so `[[Target]]` resolves to `a.c.Target`
  // rather than reporting (false) ambiguity (scalameta/metals#3383).
  test("wiki-link-nested-wildcard-shadowing".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/c/Target.scala
          |package a.c
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b._
          |object Def {
          |  import a.c._
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/c/Target.scala")), uri.toString)
  }

  // An explicit import outranks a same-package sibling defined in another file:
  // `[[Target]]` resolves to the imported `a.x.Target`, not the sibling
  // `a.Target`, matching Scala's binding precedence (scalameta/metals#3383).
  test("wiki-link-import-beats-package-sibling".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/x/Target.scala
          |package a.x
          |class Target
          |/a/src/main/scala/a/Sibling.scala
          |package a
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.x.Target
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/x/Target.scala")), uri.toString)
  }

  // An unimport hides a name from its wildcard, so `[[Target]]` after
  // `import a.b.{Target => _, _}` does not resolve through that wildcard and the
  // link does not navigate (scalameta/metals#3383).
  test("wiki-link-unimport-no-navigation".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b.{Target => _, _}
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.isEmpty, uri.toString)
  }

  // A same-compilation-unit definition outranks an import: a sibling `Target`
  // in the same file beats `import b.Target`, matching Scala precedence
  // (scalameta/metals#3383).
  test("wiki-link-same-file-beats-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/b/Target.scala
          |package b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import b.Target
          |class Target
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Def.scala")), uri.toString)
  }

  // Two explicit imports of the same name in one scope are ambiguous, so the
  // link does not navigate to an arbitrary one (scalameta/metals#3383).
  test("wiki-link-same-scope-import-ambiguity".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/x/Target.scala
          |package x
          |class Target
          |/a/src/main/scala/y/Target.scala
          |package y
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import x.Target
          |import y.Target
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.isEmpty, uri.toString)
  }

  // A capitalized package segment is unconventional but legal; `guessFromPath`
  // assumes it is a type, so the resolver retries the package interpretation and
  // `[[com.Example.Widget]]` still resolves (scalameta/metals#3383).
  test("wiki-link-uppercase-package-segment".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/com/Example/Widget.scala
          |package com.Example
          |class Widget
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[com.Example.Widget]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(
      uri.exists(_.endsWith("/com/Example/Widget.scala")),
      uri.toString,
    )
  }

  // Clicking an enum-case link relies on the companion-context alternative, as
  // the cases live in the enum's companion object (scalameta/metals#3383).
  test("wiki-link-navigate-enum-case".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalaVersion":"${V.scala3}"}}
           |/a/src/main/scala/a/Color.scala
           |package a
           |/** See [[Red]]. */
           |enum Color:
           |  case Red, Green
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Color.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val c: Col@@or = Color.Red
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Color.scala")), uri.toString)
  }

  // An overloaded method link can't be disambiguated (the parameter types are
  // dropped while parsing), so clicking it does nothing rather than navigating
  // to an arbitrary overload (scalameta/metals#3383).
  test("wiki-link-overload-no-navigation".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[foo]]. */
          |  def bar: Int = 0
          |  def foo(x: Int): Int = x
          |  def foo(x: String): String = x
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.ba@@r
          |}""".stripMargin,
      )
    } yield {
      assert(uri.isEmpty, uri.toString)
      assert(
        client.showMessages.asScala.exists(
          _.getMessage().contains("Could not uniquely resolve")
        ),
        client.showMessages.toString,
      )
    }
  }

  // A class link must not fall back to its companion object: clicking `[[foo]]`
  // in a class that has no `foo` does nothing rather than navigating to the
  // companion's `foo` (scalameta/metals#3383).
  test("wiki-link-no-companion-navigation".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Foo.scala
          |package a
          |/** See [[foo]]. */
          |class Foo
          |object Foo {
          |  def foo: Int = 0
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Foo.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val x: F@@oo = new Foo
          |}""".stripMargin,
      )
    } yield {
      assert(uri.isEmpty, uri.toString)
      assert(
        client.showMessages.asScala.exists(
          _.getMessage().contains("Could not uniquely resolve")
        ),
        client.showMessages.toString,
      )
    }
  }

  // A Javadoc local-member link `{@link #bar}` becomes `[[#bar]]`; the leading
  // `#` must be resolved as a member of the enclosing class rather than an
  // identifier named `#bar` (scalameta/metals#3383).
  test("wiki-link-navigate-java-member".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  /** See {@link #bar}. */
          |  public void foo() {}
          |  public void bar() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  /** See {@link #bar}. */
          |  public void fo@@o() {}
          |  public void bar() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Foo.java")), uri.toString)
  }

  // A qualified Javadoc member link `{@link Bar#baz}` becomes `[[Bar#baz]]`; the
  // `#` is a type/member separator, not part of an identifier, so it must
  // resolve to the member of `Bar` (scalameta/metals#3383).
  test("wiki-link-navigate-java-qualified-member".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Bar.java
          |package a;
          |public class Bar {
          |  public void baz() {}
          |}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  /** See {@link Bar#baz}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  /** See {@link Bar#baz}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Bar.java")), uri.toString)
  }

  // A Javadoc constructor link `{@link Bar#Bar(int)}` names the constructor after
  // the class; it must resolve to the constructor (`<init>`) of `Bar`
  // (scalameta/metals#3383).
  test("wiki-link-navigate-java-constructor".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Bar.java
          |package a;
          |public class Bar {
          |  public Bar(int x) {}
          |}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  /** See {@link Bar#Bar(int)}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  /** See {@link Bar#Bar(int)}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Bar.java")), uri.toString)
  }

  // A qualified link to a nested Java type `{@link Outer.Inner#method()}` must
  // resolve the nested type as `Outer#Inner` rather than the object-member form
  // `Outer.Inner` (scalameta/metals#3383).
  test("wiki-link-navigate-java-nested-type".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Outer.java
          |package a;
          |public class Outer {
          |  public static class Inner {
          |    public void method() {}
          |  }
          |}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  /** See {@link Outer.Inner#method()}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  /** See {@link Outer.Inner#method()}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Outer.java")), uri.toString)
  }

  // A bare link to a nested Java type `{@link Outer.Inner}` (no member) must
  // resolve the nested type as `Outer#Inner` (scalameta/metals#3383).
  test("wiki-link-navigate-java-nested-type-bare".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Outer.java
          |package a;
          |public class Outer {
          |  public static class Inner {}
          |}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  /** See {@link Outer.Inner}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  /** See {@link Outer.Inner}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Outer.java")), uri.toString)
  }

  // A Javadoc link to a Java member named after a Scala keyword (`Bar#match()`)
  // must resolve: Java SemanticDB stores it unwrapped, not backtick-wrapped by
  // Scala rules (scalameta/metals#3383).
  test("wiki-link-navigate-java-keyword-member".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Bar.java
          |package a;
          |public class Bar {
          |  public void match() {}
          |}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  /** See {@link Bar#match()}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  /** See {@link Bar#match()}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Bar.java")), uri.toString)
  }

  // Documentation text that happens to mention the sentinel scheme must be left
  // untouched: the real marker is prefixed by a private-use code point, so prose
  // can never collide with it (scalameta/metals#3383).
  test("wiki-link-prose-sentinel-preserved".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /**
          |    * Rendered as [x](metals-wiki-link:y) internally.
          |    */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      hover <- server.hover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        workspace,
      )
    } yield assert(
      hover.contains("[x](metals-wiki-link:y)"),
      hover,
    )
  }

  // A Javadoc same-class constructor link `{@link #Foo(int)}` resolves to the
  // enclosing class's constructor (scalameta/metals#3383).
  test("wiki-link-navigate-java-same-class-constructor".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  public Foo(int x) {}
          |  /** See {@link #Foo(int)}. */
          |  public void method() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  public Foo(int x) {}
          |  /** See {@link #Foo(int)}. */
          |  public void meth@@od() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Foo.java")), uri.toString)
  }

  // When a link resolves ambiguously (here `[[Outer.Inner]]` matches both the
  // companion object's member and the class's nested type), clicking reports
  // ambiguity rather than jumping to an arbitrary one (scalameta/metals#3383).
  test("wiki-link-ambiguous-no-navigation".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Outer.scala
          |package a
          |class Outer { class Inner }
          |object Outer { object Inner }
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[Outer.Inner]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Outer.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield {
      assert(uri.isEmpty, uri.toString)
      assert(
        client.showMessages.asScala.exists(
          _.getMessage().contains("Could not uniquely resolve")
        ),
        client.showMessages.toString,
      )
    }
  }

  // Per the JLS, a single-type import shadows a same-named type declared in
  // another compilation unit of the package, so `{@link Helper}` resolves to the
  // imported `a.b.Helper`, not the same-package sibling `a.Helper`
  // (scalameta/metals#3383).
  test("wiki-link-java-import-beats-package-sibling".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/b/Helper.java
          |package a.b;
          |public class Helper {}
          |/a/src/main/java/a/Helper.java
          |package a;
          |public class Helper {}
          |/a/src/main/java/a/Foo.java
          |package a;
          |import a.b.Helper;
          |public class Foo {
          |  /** See {@link Helper}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |import a.b.Helper;
          |public class Foo {
          |  /** See {@link Helper}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Helper.java")), uri.toString)
  }

  // Imports are positionally scoped within a template: an `import` placed between
  // two documented members applies only to the member after it, so the earlier
  // member does not see it (this exercises the per-source memoized scope snapshot)
  // (scalameta/metals#3383).
  test("wiki-link-mid-scope-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/x/Target.scala
          |package a.x
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[Target]]. */
          |  def early(i: Int): Int = ???
          |  import a.x.Target
          |  /** See [[Target]]. */
          |  def late(i: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      lateUri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.la@@te(1)
          |}""".stripMargin,
      )
      earlyUri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.ea@@rly(1)
          |}""".stripMargin,
      )
    } yield {
      assert(lateUri.exists(_.endsWith("/a/x/Target.scala")), lateUri.toString)
      // `early` precedes the import, so it must not resolve through it.
      assert(earlyUri.isEmpty, earlyUri.toString)
    }
  }

  // A relative import prefix is resolved against the enclosing package: in
  // `package a`, `import b.Target` means `a.b.Target`, so `[[Target]]` navigates
  // there even though the literal prefix is just `b` (scalameta/metals#3383).
  test("wiki-link-navigate-relative-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import b.Target
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Target.scala")), uri.toString)
  }

  // A member of a package object resolves from a bare link in another file of the
  // package, since the `pkg/package.member` form is synthesized after the
  // enclosing package qualifies the name (scalameta/metals#3383).
  test("wiki-link-navigate-bare-package-object-member".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/pkg.scala
          |package a
          |package object p {
          |  val answer: Int = 42
          |}
          |/a/src/main/scala/a/p/Other.scala
          |package a.p
          |object Other {
          |  /** See [[answer]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/pkg.scala")
      _ <- server.didSave("a/src/main/scala/a/p/Other.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = a.p.Other.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/pkg.scala")), uri.toString)
  }

  // A Javadoc link parses by the documentation's own language, not the hovered
  // file's: `$` is an ordinary Java identifier character, so `{@link Money$}`
  // resolves to the Java class rather than being read as a value-force suffix
  // (scalameta/metals#3383).
  test("wiki-link-navigate-java-dollar-type".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/Money$.java
          |package a;
          |public class Money$ {}
          |/a/src/main/java/a/Foo.java
          |package a;
          |public class Foo {
          |  /** See {@link Money$}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |public class Foo {
          |  /** See {@link Money$}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Money$.java")), uri.toString)
  }

  // A symbolic-or-dotted escaped identifier brought in by a wildcard import is
  // still a single name, so `` [[`Foo.Bar`]] `` after `import a.p.syntax._`
  // navigates to it (scalameta/metals#3383).
  test("wiki-link-escaped-dotted-wildcard".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/syntax.scala
          |package a.p
          |object syntax {
          |  class `Foo.Bar`
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.p.syntax._
          |object Def {
          |  /** See [[`Foo.Bar`]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/syntax.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/syntax.scala")), uri.toString)
  }

  // A static on-demand import (`import static Util.*`) brings in static members,
  // so `{@link helper}` resolves to the static method (scalameta/metals#3383).
  test("wiki-link-navigate-java-static-wildcard".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/b/Util.java
          |package a.b;
          |public class Util {
          |  public static int helper() { return 0; }
          |}
          |/a/src/main/java/a/Foo.java
          |package a;
          |import static a.b.Util.*;
          |public class Foo {
          |  /** See {@link helper}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |import static a.b.Util.*;
          |public class Foo {
          |  /** See {@link helper}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Util.java")), uri.toString)
  }

  // Per the JLS, an on-demand import (`import a.b.*`) and the implicit
  // `java.lang.*` are equal precedence, so a name in both (`String`) is ambiguous
  // and does not navigate (scalameta/metals#3383).
  test("wiki-link-java-wildcard-vs-java-lang-ambiguity".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/java/a/b/String.java
          |package a.b;
          |public class String {}
          |/a/src/main/java/a/Foo.java
          |package a;
          |import a.b.*;
          |public class Foo {
          |  /** See {@link String}. */
          |  public void foo() {}
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      uri <- clickFirstLink(
        "a/src/main/java/a/Foo.java",
        """package a;
          |import a.b.*;
          |public class Foo {
          |  /** See {@link String}. */
          |  public void fo@@o() {}
          |}""".stripMargin,
      )
    } yield assert(uri.isEmpty, uri.toString)
  }

  // A same-package definition from another file shadows the implicit/compiler
  // scope, so a same-package `class String` beats `java.lang.String`
  // (scalameta/metals#3383).
  test("wiki-link-same-package-beats-root".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/MyString.scala
          |package a
          |class String
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[String]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/MyString.scala")), uri.toString)
  }

  // A relative wildcard import is resolved against the enclosing package: in
  // `package a`, `import b._` brings in `a.b`, so `[[Target]]` navigates to
  // `a.b.Target` (scalameta/metals#3383).
  test("wiki-link-relative-wildcard-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import b._
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Target.scala")), uri.toString)
  }

  // A wildcard on a prefix alias resolves through the rename: after
  // `import a.p.{util => u}; import u._`, `[[Tool]]` navigates to `a.p.util.Tool`
  // (scalameta/metals#3383).
  // An EXPLICIT import through a rename-alias is expanded too: `import a.p.{util =>
  // u}; import u.Tool` makes `[[Tool]]` resolve to `a.p.util.Tool` (not the bogus
  // `u.Tool`) — alias expansion is no longer wildcard-only (scalameta/metals#3383).
  test("wiki-link-aliased-explicit-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/util.scala
          |package a.p
          |object util {
          |  class Tool
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.p.{util => u}
          |import u.Tool
          |object Def {
          |  /** See [[Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/util.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/util.scala")), uri.toString)
  }

  // Only the LEADING segment of an import prefix needs to be the alias: with
  // `import a.p.{util => u}; import u.syntax.Tool`, `[[Tool]]` resolves to
  // `a.p.util.syntax.Tool` (the `u` segment is expanded, the rest of the path
  // kept), not the bogus `u.syntax.Tool` (scalameta/metals#3383).
  test("wiki-link-aliased-nested-explicit-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/util.scala
          |package a.p
          |object util {
          |  object syntax {
          |    class Tool
          |  }
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.p.{util => u}
          |import u.syntax.Tool
          |object Def {
          |  /** See [[Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/util.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/util.scala")), uri.toString)
  }

  // A CHAINED alias resolves through every hop: `import a.p.{util => u}` then
  // `import u.{syntax => s}` then `import s.Tool` — `s` must expand to
  // `a.p.util.syntax`, not the unexpanded `u.syntax`, so `[[Tool]]` resolves
  // (scalameta/metals#3383).
  test("wiki-link-chained-alias-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/util.scala
          |package a.p
          |object util {
          |  object syntax {
          |    class Tool
          |  }
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.p.{util => u}
          |import u.{syntax => s}
          |import s.Tool
          |object Def {
          |  /** See [[Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/util.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/util.scala")), uri.toString)
  }

  // A BACKTICKED alias prefix expands: `` import a.p.{util => `type`} `` binds the
  // alias `type` (a keyword, so it must be escaped), and `` import `type`.Tool ``
  // uses it — the head `` `type` `` must be unescaped before it is matched against
  // the alias key `type`, so `[[Tool]]` resolves to `a.p.util.Tool`
  // (scalameta/metals#3383).
  test("wiki-link-backticked-alias-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/util.scala
          |package a.p
          |object util {
          |  class Tool
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.p.{util => `type`}
          |import `type`.Tool
          |object Def {
          |  /** See [[Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/util.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/util.scala")), uri.toString)
  }

  // An import inherited from an OUTER package scope resolves against the package
  // where IT is written, not the documented symbol's package: `import p.Target`
  // sits in `package a`, so it expands to `a.p.Target` even though the doc using
  // `[[Target]]` lives in the nested `package b` (`a.b`) — NOT the bogus
  // `a.b.p.Target` (scalameta/metals#3383).
  test("wiki-link-outer-package-relative-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/Target.scala
          |package a.p
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import p.Target
          |package b {
          |  object Def {
          |    /** See [[Target]]. */
          |    def foo(x: Int): Int = ???
          |  }
          |}
          |/a/src/main/scala/a/Main.scala
          |package a.b
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/Target.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a.b
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/Target.scala")), uri.toString)
  }

  // `_root_` detection is the real root selector, not any name that merely starts
  // with those characters: `import _root_foo.Target` inside `package a` is an
  // ordinary RELATIVE import, so `[[Target]]` resolves to `a._root_foo.Target`
  // (scalameta/metals#3383).
  test("wiki-link-root-lookalike-relative-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/_root_foo/Target.scala
          |package a._root_foo
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import _root_foo.Target
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/_root_foo/Target.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(
      uri.exists(_.endsWith("/a/_root_foo/Target.scala")),
      uri.toString,
    )
  }

  // An import written OUTSIDE a `package object` resolves against the enclosing
  // package, not the package object's: `import p.Target` sits in `package a`, so it
  // expands to `a.p.Target` even though the doc using `[[Target]]` lives in the
  // `package object q` (whose members are in `a.q`) — NOT `a.q.p.Target`. Guards
  // that the climb strips the `Pkg.Object` segment (scalameta/metals#3383).
  test("wiki-link-outer-import-in-package-object".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/Target.scala
          |package a.p
          |class Target
          |/a/src/main/scala/a/q/package.scala
          |package a
          |import p.Target
          |package object q {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a.q
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/Target.scala")
      _ <- server.didSave("a/src/main/scala/a/q/package.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a.q
          |object Main {
          |  val res = fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/Target.scala")), uri.toString)
  }

  // A keyword-named package block (`` package `type` ``) is stripped from the scope
  // package when the climb leaves it, even though scalameta re-escapes it in
  // `ref.syntax`: the root-level `import q.Widget` resolves against the ROOT (only
  // `q.Widget`), NOT a spurious `` `type`.q.Widget `` — which would be a false match
  // here since `type.q.Widget` also exists (scalameta/metals#3383).
  test("wiki-link-keyword-package-outer-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{"scalaVersion":"3.3.8"}}
          |/a/src/main/scala/q/Widget.scala
          |package q
          |class Widget
          |/a/src/main/scala/kw/Widget.scala
          |package `type`.q
          |class Widget
          |/a/src/main/scala/a/Def.scala
          |import q.Widget
          |package `type` {
          |  object Def {
          |    /** See [[Widget]]. */
          |    def foo(x: Int): Int = ???
          |  }
          |}
          |/a/src/main/scala/a/Main.scala
          |package `type`
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/q/Widget.scala")
      _ <- server.didSave("a/src/main/scala/kw/Widget.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package `type`
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/q/Widget.scala")), uri.toString)
  }

  // A grouped/bare `_root_` import binds at the root: `` import _root_.{p => q} ``
  // makes `q` the package `p`, so `[[q.Target]]` resolves to `p.Target`, NOT the
  // nonexistent `_root_.p.Target` (scalameta/metals#3383).
  test("wiki-link-root-grouped-alias-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/p/Target.scala
          |package p
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import _root_.{p => q}
          |object Def {
          |  /** See [[q.Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/p/Target.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/p/Target.scala")), uri.toString)
  }

  // Same-scope alias expansion respects source order: `import u.Tool` (where `u` is
  // the package `a.u`) is NOT re-bound by a LATER same-scope `import a.p.{util =>
  // u}` that wasn't visible when it was parsed, so `[[Tool]]` resolves to
  // `a.u.Tool`, not the bogus `a.p.util.Tool` (scalameta/metals#3383).
  test("wiki-link-alias-respects-source-order".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/u/Tool.scala
          |package a.u
          |class Tool
          |/a/src/main/scala/a/p/util.scala
          |package a.p
          |object util {
          |  class Other
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import u.Tool
          |import a.p.{util => u}
          |object Def {
          |  /** See [[Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/u/Tool.scala")
      _ <- server.didSave("a/src/main/scala/a/p/util.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/u/Tool.scala")), uri.toString)
  }

  test("wiki-link-aliased-wildcard-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/util.scala
          |package a.p
          |object util {
          |  class Tool
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.p.{util => u}
          |import u._
          |object Def {
          |  /** See [[Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/util.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/util.scala")), uri.toString)
  }

  // An OUTER-scope alias is visible to an INNER-scope wildcard of it: with
  // `import a.p.{util => u}` at the file and `import u._` inside the object, a bare
  // `[[Tool]]` still resolves to `a.p.util.Tool` (scalameta/metals#3383).
  test("wiki-link-outer-alias-inner-wildcard".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/p/util.scala
          |package a.p
          |object util {
          |  class Tool
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.p.{util => u}
          |object Def {
          |  import u._
          |  /** See [[Tool]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/p/util.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/p/util.scala")), uri.toString)
  }

  // An inherited member link fails SAFELY: `[[Widget#paint]]`, where the same-file
  // `Widget` only INHERITS `paint` (so it can't be resolved here), must NOT fall
  // through to an unrelated `import other.Widget` that declares its own `paint`
  // (scalameta/metals#3383).
  test("wiki-link-inherited-member-safe-failure".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/other/Widget.scala
          |package other
          |class Widget {
          |  def paint(): Unit = ()
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import other.Widget
          |class Base {
          |  def paint(): Unit = ()
          |}
          |class Widget extends Base
          |object Def {
          |  /** See [[Widget#paint]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/other/Widget.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(
      !uri.exists(_.contains("/other/Widget.scala")),
      s"inherited member link wrongly navigated to an unrelated import: $uri",
    )
  }

  // An inherited docstring must refresh when the PARENT's file is edited, even
  // though the child's own file is unchanged. The merged parent doc is no longer
  // cached under the child's key, so it is re-derived from the re-indexed parent —
  // without this, the child kept showing the parent's stale text (and stale encoded
  // link scope) until restart (scalameta/metals#3383).
  test("wiki-link-inherited-doc-refresh".tag(FlakyWindows)) {
    val query =
      """
        |package a
        |object Main {
        |  val res = new Child().fo@@o(1)
        |}""".stripMargin
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Parent.scala
          |package a
          |class Parent {
          |  /** parent-docs-v1 */
          |  def foo(x: Int): Int = x
          |}
          |/a/src/main/scala/a/Child.scala
          |package a
          |class Child extends Parent {
          |  override def foo(x: Int): Int = x
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Parent.scala")
      _ <- server.didSave("a/src/main/scala/a/Child.scala")
      before <- server.hover("a/src/main/scala/a/Main.scala", query, workspace)
      _ = assert(before.contains("parent-docs-v1"), before)
      // Edit ONLY the parent's file.
      _ <- server.didChange("a/src/main/scala/a/Parent.scala")(
        _.replace("parent-docs-v1", "parent-docs-v2")
      )
      _ <- server.didSave("a/src/main/scala/a/Parent.scala")
      after <- server.hover("a/src/main/scala/a/Main.scala", query, workspace)
      _ = assert(after.contains("parent-docs-v2"), s"stale child doc: $after")
      _ = assert(!after.contains("parent-docs-v1"), s"stale child doc: $after")
    } yield ()
  }

  // Per Scala, an OUTER explicit import and an INNER wildcard import that both bind
  // a name do NOT shadow each other, so the name is AMBIGUOUS — `[[Target]]` here
  // navigates nowhere instead of guessing the explicit `x.Target`
  // (scalameta/metals#3383).
  test("wiki-link-outer-explicit-inner-wildcard-ambiguous".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/x/Target.scala
          |package x
          |class Target
          |/a/src/main/scala/y/Target.scala
          |package y
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import x.Target
          |object Def {
          |  import y._
          |  /** See [[Target]]. */
          |  def foo(z: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/x/Target.scala")
      _ <- server.didSave("a/src/main/scala/y/Target.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.isEmpty, s"ambiguous link should not navigate: $uri")
  }

  // An owner bound by an EXPLICIT import caps the member: `[[Widget#paint]]` where
  // `import p.Widget` only INHERITS `paint` (so it misses) must NOT fall through to
  // a same-scope `import q._` whose `q.Widget` declares its own `paint`
  // (scalameta/metals#3383).
  test("wiki-link-imported-inherited-member-capped".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/p/Widget.scala
          |package p
          |class Base { def paint(): Unit = () }
          |class Widget extends Base
          |/a/src/main/scala/q/Widget.scala
          |package q
          |class Widget { def paint(): Unit = () }
          |/a/src/main/scala/a/Def.scala
          |package a
          |import p.Widget
          |import q._
          |object Def {
          |  /** See [[Widget#paint]]. */
          |  def foo(z: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/p/Widget.scala")
      _ <- server.didSave("a/src/main/scala/q/Widget.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(
      !uri.exists(_.contains("/q/Widget.scala")),
      s"capped owner wrongly navigated to an unrelated import: $uri",
    )
  }

  // When the owner NAME is itself ambiguous (an OUTER explicit and an INNER
  // wildcard both bind `Widget`, neither shadowing the other), a member link fails
  // SAFELY — `[[Widget#paint]]` does NOT jump to whichever Widget happens to
  // declare `paint` (scalameta/metals#3383).
  test("wiki-link-ambiguous-owner-member-safe-failure".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/x/Widget.scala
          |package x
          |class Widget
          |/a/src/main/scala/y/Widget.scala
          |package y
          |class Widget { def paint(): Unit = () }
          |/a/src/main/scala/a/Def.scala
          |package a
          |import x.Widget
          |object Def {
          |  import y._
          |  /** See [[Widget#paint]]. */
          |  def foo(z: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/x/Widget.scala")
      _ <- server.didSave("a/src/main/scala/y/Widget.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(
      uri.isEmpty,
      s"ambiguous owner should not navigate for a member link: $uri",
    )
  }

  // A `_root_`-anchored import is absolute: its prefix is stripped so
  // `import _root_.a.b.Target` resolves like `import a.b.Target`
  // (scalameta/metals#3383).
  test("wiki-link-navigate-root-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import _root_.a.b.Target
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/b/Target.scala")), uri.toString)
  }

  // A renamed selector hides the original name even from a trailing wildcard:
  // `import a.b.{Target => Renamed, _}` binds `Renamed`, not `Target`, so
  // `[[Target]]` does not navigate (scalameta/metals#3383).
  test("wiki-link-rename-hides-original-wildcard".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/b/Target.scala
          |package a.b
          |class Target
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.b.{Target => Renamed, _}
          |object Def {
          |  /** See [[Target]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.isEmpty, uri.toString)
  }

  // A member of a package object resolves through the synthesized
  // `pkg/package.member` form, generalized beyond the `scala` package object
  // (scalameta/metals#3383).
  test("wiki-link-navigate-package-object-member".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/util/util.scala
          |package a
          |package object util {
          |  val answer: Int = 42
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[a.util.answer]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/util/util.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/util/util.scala")), uri.toString)
  }

  // A parameterized enum case is a case class; its docstring's relative links
  // resolve against the case itself, so `[[r]]` finds the case parameter rather
  // than falling through to the enum's companion (scalameta/metals#3383).
  test("wiki-link-navigate-enum-case-param".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalaVersion":"${V.scala3}"}}
           |/a/src/main/scala/a/Color.scala
           |package a
           |enum Color:
           |  /** A component [[r]]. */
           |  case Mix(r: Int)
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Color.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val m = Color.Mi@@x(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Color.scala")), uri.toString)
  }

  // Boundary recovery flips several `/`<->`.` boundaries at once, so a chain of
  // nested lowercase objects (`outer.inner.Box`, which `guessFromPath` commits to
  // packages) still resolves (scalameta/metals#3383).
  test("wiki-link-multi-boundary-recovery".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Nested.scala
          |package a
          |object outer {
          |  object inner {
          |    class Box
          |  }
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /** See [[outer.inner.Box]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Nested.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/Nested.scala")), uri.toString)
  }

  // A symbolic operator brought in by a wildcard import is qualifiable too, so
  // `` [[`===`]] `` after `import a.ops.syntax._` navigates to the operator
  // (scalameta/metals#3383).
  test("wiki-link-operator-wildcard-import".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/ops/syntax.scala
          |package a.ops
          |object syntax {
          |  def ===(x: Int): Boolean = true
          |}
          |/a/src/main/scala/a/Def.scala
          |package a
          |import a.ops.syntax._
          |object Def {
          |  /** See [[`===`]]. */
          |  def foo(x: Int): Int = ???
          |}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/ops/syntax.scala")
      _ <- server.didSave("a/src/main/scala/a/Def.scala") // index docs
      uri <- clickFirstLink(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
      )
    } yield assert(uri.exists(_.endsWith("/a/ops/syntax.scala")), uri.toString)
  }

}
