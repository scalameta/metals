package tests.mcp

import scala.meta.internal.metals.mcp.McpPrinter._

import org.eclipse.{lsp4j => l}
import tests.BaseLspSuite

class McpShowImplicitsLspSuite
    extends BaseLspSuite("show-implicits")
    with McpTestUtils {

  test("implicit-conversion") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/a/src/main/scala/Conv.scala
            |object Conv {
            |  val reversed = "abc".reverse
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Conv.scala")
      _ = assertNoDiagnostics()
      path = server.toPath("a/src/main/scala/Conv.scala")
      result <- server.headServer.queryEngine.showImplicits(Some(path), None)
      // Exactly one rendered conversion line: the standalone `)` close hint is
      // filtered out, so only the meaningful opening hint remains.
      _ = assertNoDiff(
        result.show,
        """|Implicit conversions:
           |  a/src/main/scala/Conv.scala:2:18  augmentString  ->  scala/Predef.augmentString().
           |""".stripMargin,
      )
    } yield ()
  }

  test("multi-target-implicit-arguments") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/a/src/main/scala/Instances.scala
            |trait Eq[A]
            |trait Semigroup[A]
            |object Instances {
            |  implicit def instancesString: Eq[String] with Semigroup[String] = ???
            |}
            |object Demo {
            |  import Instances._
            |  def checkThing2[A](implicit ev: Eq[A], sem: Semigroup[A]): Unit = ()
            |  val x = checkThing2[String]
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Instances.scala")
      _ = assertNoDiagnostics()
      path = server.toPath("a/src/main/scala/Instances.scala")
      result <- server.headServer.queryEngine.showImplicits(Some(path), None)
      // One implicit-argument hint whose two label parts both resolve to the
      // in-file `instancesString` def; the `(`, `, `, `)` separator parts must
      // NOT leak as empty targets.
      _ = assertNoDiff(
        result.show,
        """|Implicit arguments:
           |  a/src/main/scala/Instances.scala:9:30  (instancesString, instancesString)  ->  (4:16), (4:16)
           |""".stripMargin,
      )
    } yield ()
  }

  test("range-narrowing-and-clamping") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/a/src/main/scala/Ranges.scala
            |object Ranges {
            |  val sorted = List(3, 1, 2).sorted
            |  val reversed = "abc".reverse
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Ranges.scala")
      _ = assertNoDiagnostics()
      path = server.toPath("a/src/main/scala/Ranges.scala")
      // Narrow to line 1 (0-based) only -> just the implicit Ordering argument.
      narrowed <- server.headServer.queryEngine.showImplicits(
        Some(path),
        Some(new l.Range(new l.Position(1, 0), new l.Position(1, 0))),
      )
      _ = assertNoDiff(
        narrowed.show,
        """|Implicit arguments:
           |  a/src/main/scala/Ranges.scala:2:36  (Int)  ->  scala/math/Ordering.Int.
           |""".stripMargin,
      )
      // Deliberately out-of-bounds endLine is clamped, not dropped: still returns
      // the in-file hints across the whole file.
      clamped <- server.headServer.queryEngine.showImplicits(
        Some(path),
        Some(new l.Range(new l.Position(0, 0), new l.Position(999, 0))),
      )
      _ = assertNoDiff(
        clamped.show,
        """|Implicit arguments:
           |  a/src/main/scala/Ranges.scala:2:36  (Int)  ->  scala/math/Ordering.Int.
           |Implicit conversions:
           |  a/src/main/scala/Ranges.scala:3:18  augmentString  ->  scala/Predef.augmentString().
           |""".stripMargin,
      )
    } yield ()
  }

  test("client-range-is-1-based") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/a/src/main/scala/Ranges.scala
            |object Ranges {
            |  val sorted = List(3, 1, 2).sorted
            |  val reversed = "abc".reverse
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Ranges.scala")
      _ = assertNoDiagnostics()
      client <- startMcpServer()
      path = server.toPath("a/src/main/scala/Ranges.scala")
      // 1-based startLine/endLine of 2 brackets only the `sorted` line, which is
      // exactly the 1-based line reported in the hint below -- locks in that the
      // public range inputs are now 1-based, matching the output convention.
      (isError, texts) <- client.showImplicits(
        Some(path.toString),
        startLine = Some(2),
        endLine = Some(2),
      )
      _ = assert(!isError)
      _ = assertNoDiff(
        texts.mkString,
        """|Implicit arguments:
           |  a/src/main/scala/Ranges.scala:2:36  (Int)  ->  scala/math/Ordering.Int.
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("no-file-resolved") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/a/src/main/scala/Conv.scala
            |object Conv {
            |  val reversed = "abc".reverse
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Conv.scala")
      // No path and no way to resolve one -> NoFile, rendered as an error message.
      result <- server.headServer.queryEngine.showImplicits(None, None)
      _ = assertNoDiff(
        result.show,
        "Error: could not resolve file: <no file provided>",
      )
    } yield ()
  }

  test("no-implicits-found") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/a/src/main/scala/Plain.scala
            |object Plain {
            |  val x: Int = 1 + 2
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Plain.scala")
      _ = assertNoDiagnostics()
      path = server.toPath("a/src/main/scala/Plain.scala")
      result <- server.headServer.queryEngine.showImplicits(Some(path), None)
      _ = assertNoDiff(
        result.show,
        "No implicits found in a/src/main/scala/Plain.scala",
      )
    } yield ()
  }

  // Only the client-level path exercises schema parsing, tool registration, and
  // the isError branch (engine-only tests cannot reach these).
  test("is-error-wiring") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/a/src/main/scala/Conv.scala
            |object Conv {
            |  val reversed = "abc".reverse
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Conv.scala")
      _ = assertNoDiagnostics()
      client <- startMcpServer()
      path = server.toPath("a/src/main/scala/Conv.scala")
      (normalIsError, _) <- client.showImplicits(Some(path.toString))
      _ = assert(!normalIsError, "normal file should not be an error")
      missing = path.toNIO.resolveSibling("DoesNotExist.scala").toString
      (missingIsError, _) <- client.showImplicits(Some(missing))
      _ = assert(missingIsError, "missing file should be an error")
      _ <- client.shutdown()
    } yield ()
  }
}
