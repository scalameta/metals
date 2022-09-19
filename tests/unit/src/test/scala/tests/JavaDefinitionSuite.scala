package tests

import java.net.URI
import java.nio.charset.StandardCharsets

import scala.util.Properties

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.io.AbsolutePath

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}

class JavaDefinitionSuite extends BaseLspSuite("java-definition") {

  val javaBasePrefix: String =
    if (Properties.isJavaAtLeast("9")) "java.base/" else ""

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  check(
    "jdk-String",
    "java.lang.String",
    s"""|${javaBasePrefix}java/lang/String.java
        |public String replace(@@CharSequence target, CharSequence replacement) {
        |""".stripMargin,
    s"""|src.zip/${javaBasePrefix}java/lang/CharSequence.java info: result
        |public interface CharSequence {
        |                 ^^^^^^^^^^^^
        |""".stripMargin,
    withoutVirtualDocs = true,
  )

  check(
    "jdk-String-patch-module",
    "java.lang.String",
    s"""|${javaBasePrefix}java/lang/String.java
        |private boolean nonSyncContentEquals(@@AbstractStringBuilder sb) {
        |""".stripMargin,
    s"""|src.zip/${javaBasePrefix}java/lang/AbstractStringBuilder.java info: result
        |abstract class AbstractStringBuilder implements Appendable, CharSequence {
        |               ^^^^^^^^^^^^^^^^^^^^^
        |""".stripMargin,
  )

  check(
    "jdk-String-patch-module",
    "java.lang.String",
    s"""|${javaBasePrefix}java/lang/String.java
        |private boolean nonSyncContentEquals(@@AbstractStringBuilder sb) {
        |""".stripMargin,
    s"""|src.zip/${javaBasePrefix}java/lang/AbstractStringBuilder.java info: result
        |abstract class AbstractStringBuilder implements Appendable, CharSequence {
        |               ^^^^^^^^^^^^^^^^^^^^^
        |""".stripMargin,
  )

  check(
    "xnio1",
    "org.xnio.nio.NioTcpServer",
    s"""|/org/xnio/nio/NioTcpServer.java
        |tcpServerLog.logf(FQCN, @@Logger.Level.TRACE, null, "Wake up accepts on %s", this);
        |""".stripMargin,
    """|jboss-logging-3.3.1.Final-sources.jar/org/jboss/logging/Logger.java info: result
       |public abstract class Logger implements Serializable, BasicLogger {
       |                      ^^^^^^
       |""".stripMargin,
    dependencies = List(
      "org.jboss.xnio:xnio-nio:3.8.8.Final"
    ),
  )

  check(
    "xnio2",
    "org.xnio.nio.Log",
    s"""|/org/xnio/nio/Log.java
        |Log log = @@Logger.getMessageLogger(Log.class, "org.xnio.nio");
        |""".stripMargin,
    """|jboss-logging-3.3.1.Final-sources.jar/org/jboss/logging/Logger.java info: result
       |public abstract class Logger implements Serializable, BasicLogger {
       |                      ^^^^^^
       |""".stripMargin,
    dependencies = List(
      "org.jboss.xnio:xnio-nio:3.8.8.Final"
    ),
  )

  def check(
      name: TestOptions,
      depSymbol: String,
      input: String,
      expected: String,
      dependencies: List[String] = Nil,
      withoutVirtualDocs: Boolean = false,
  )(implicit loc: Location): Unit = {
    test(name, withoutVirtualDocs) {
      val parsed = FileLayout.mapFromString(input)
      assert(parsed.size == 1, "Input should have only one dep source file")
      val (path, query) = parsed.head

      val deps = dependencies
        .map(s => s""""$s"""")
        .mkString("[", ", ", "]")
      val layout =
        s"""|/metals.json
            |{
            |  "a": {
            |    "libraryDependencies": $deps
            |  }
            |}
            |""".stripMargin
      for {
        _ <- initialize(layout)
        // trigger extraction into readonly
        info = server.server.workspaceSymbol(depSymbol)
        matchedInfo = info.find(_.getLocation().getUri().contains(path))
        uri = matchedInfo match {
          case None =>
            fail(
              s"Symbol ${depSymbol} with expected path ${path} has not been found"
            )
          case Some(info) =>
            info.getLocation().getUri()
        }
        pos = depSourcePosition(uri, query)

        locations <- server.server
          .definition(
            new l.TextDocumentPositionParams(
              new l.TextDocumentIdentifier(uri),
              pos,
            )
          )
          .asScala
        rendered = locations.asScala.map(renderLocation).mkString("\n")
        _ = assertNoDiff(rendered, expected)
      } yield ()
    }
  }

  private def depSourcePosition(
      uri: String,
      query: String,
  ): l.Position = {
    val characterInc = query.indexOf("@@")
    if (characterInc == -1) {
      throw new Exception("Query must contain @@")
    } else {
      val path = AbsolutePath.fromAbsoluteUri(URI.create(uri))
      val raw = query.replaceAll("@@", "").trim()
      val result = FileIO
        .slurp(path, StandardCharsets.UTF_8)
        .linesIterator
        .zipWithIndex
        .find { case (line, _) =>
          line.contains(raw)
        }
      result match {
        case Some((line, idx)) =>
          val firstCh = line.indexOf(raw)
          val ch = firstCh + characterInc
          new l.Position(idx, ch)
        case None => throw new Exception(s"Query not found in $path")
      }
    }
  }

  private def renderLocation(loc: l.Location): String = {
    val path = AbsolutePath.fromAbsoluteUri(URI.create(loc.getUri()))
    val relativePath =
      path.jarPath
        .map(jarPath => s"${jarPath.filename}${path}")
        .getOrElse(
          path
            .toRelative(workspace.resolve(Directories.dependencies))
            .toString()
        )
    val input = path.toInput.copy(path = relativePath.replace("\\", "/"))
    loc
      .getRange()
      .toMeta(input)
      .getOrElse(
        throw new RuntimeException(
          s"Range ${loc.getRange()} is not available for ${loc.getUri()}"
        )
      )
      .formatMessage("info", "result", noPos = true)
  }
}
