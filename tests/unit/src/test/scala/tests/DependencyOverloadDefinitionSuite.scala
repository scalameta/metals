package tests

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

import scala.concurrent.Future
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PackageIndex
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

/** Dependency-source typing must preserve the overload selected in a cached caller. */
class DependencyOverloadDefinitionSuite
    extends BaseLspSuite("dependency-overload-definition")
    with BaseSourcePathSuite {
  private val dependencySource =
    """|package fixture
       |import scala.concurrent.Future
       |final case class Content(body: String)
       |final case class Result(body: String)
       |final class Timeout
       |trait Codec
       |object NoCodec extends Codec
       |trait Renderers {
       |  def render(of: Content)(implicit timeout: Timeout): String = of.body
       |  def render(of: Future[Result])(implicit timeout: Timeout, codec: Codec = NoCodec): String = "future"
       |}
       |object Helpers extends Renderers
       |""".stripMargin

  private def dependencyDocument(
      source: AbsolutePath,
      text: String,
  ): TextDocument = {
    val compiler = server.headServer.compilers
      .loadCompiler(source)
      .getOrElse(fail("dependency compiler unavailable"))
    val bytes = compiler
      .semanticdbTextDocument(
        CompilerVirtualFileParams(source.toURI, text)
      )
      .get(30, TimeUnit.SECONDS)
    TextDocument.parseFrom(bytes)
  }

  private def dependencyRepository(): String = {
    val root = workspace.resolve("repository").toNIO
    val artifact = root.resolve("fixture/frozen-overloads_2.13/1.0.0")
    Files.createDirectories(artifact)
    val settings = new Settings()
    settings.classpath.value =
      PackageIndex.scalaLibrary.mkString(File.pathSeparator)
    settings.d.value =
      artifact.resolve("frozen-overloads_2.13-1.0.0.jar").toString
    val reporter = new StoreReporter(settings)
    val compiler = new Global(settings, reporter)
    new compiler.Run().compileSources(
      List(new BatchSourceFile("Helpers.scala", dependencySource))
    )
    assert(!reporter.hasErrors, reporter.infos.mkString("\n"))
    val sourceJar = new JarOutputStream(
      Files.newOutputStream(
        artifact.resolve("frozen-overloads_2.13-1.0.0-sources.jar")
      )
    )
    try {
      sourceJar.putNextEntry(new JarEntry("fixture/Helpers.scala"))
      sourceJar.write(dependencySource.getBytes(UTF_8))
      sourceJar.closeEntry()
    } finally sourceJar.close()
    Files.writeString(
      artifact.resolve("frozen-overloads_2.13-1.0.0.pom"),
      """<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>frozen-overloads_2.13</artifactId><version>1.0.0</version></project>""",
    )
    root.toUri.toString
  }

  for {
    fullService <- List(false, true)
    hydrateDependencySemanticdb <-
      if (fullService) List(false) else List(false, true)
  } {
    test(
      s"dependency-overload-symbol-and-location-service-$fullService-hydrate-$hydrateDependencySemanticdb"
    ) {
      cleanWorkspace()
      val repository = dependencyRepository()
      val caller = "a/src/main/scala/Caller.scala"
      val queries = List(
        ("ren@@der(Content(\"x\"))", "fixture/Renderers#render().", 8),
        (
          "ren@@der(Future.successful(Result(\"y\")))",
          "fixture/Renderers#render(+1).",
          9,
        ),
      )
      def check(phase: String): Future[String] =
        queries.foldLeft(Future.successful("")) {
          case (previous, (query, symbol, line)) =>
            previous.flatMap { _ =>
              val params = server.textDocumentPositionParams(caller, query)
              val answer =
                if (fullService) server.headServer.definitionResult(params)
                else
                  server.headServer.compilers
                    .definition(params, EmptyCancelToken)
              answer
                .map { result =>
                  assertEquals(
                    (result.symbol, result.querySymbol),
                    (symbol, symbol),
                    s"service=$fullService phase=$phase query=$query locations=${result.locations}",
                  )
                  assertEquals(result.locations.size(), 1)
                  val location = result.locations.get(0)
                  assertEquals(location.getRange.getStart.getLine, line)
                  assert(location.getUri.endsWith("/fixture/Helpers.scala"))
                  if (hydrateDependencySemanticdb) {
                    val dependency = location.getUri.toAbsolutePath
                    assertEquals(
                      server.headServer.buildTargets.inverseSources(dependency),
                      server.headServer.buildTargets.inverseSources(
                        workspace.resolve(caller)
                      ),
                    )
                    val document =
                      dependencyDocument(dependency, dependency.readText)
                    assert(document.symbols.nonEmpty)
                  }
                  location.getUri
                }
            }
        }
      def awaitAssociation(uri: String, attempts: Int = 500): Future[Unit] = {
        val expected = server.headServer.buildTargets
          .inverseSources(workspace.resolve(caller))
        assert(expected.nonEmpty)
        val actual = server.headServer.tables.dependencySources
          .getBuildTarget(uri.toAbsolutePath)
        if (actual == expected) Future.successful(())
        else if (attempts == 0)
          Future.failed(
            new AssertionError(
              s"dependency association missing: $actual != $expected"
            )
          )
        else
          // Poll the callback's persisted state, not elapsed time as readiness.
          server.waitFor(10).flatMap(_ => awaitAssociation(uri, attempts - 1))
      }
      for {
        _ <- initialize(
          s"""|/metals.json
              |{"a":{"scalaVersion":"${BuildInfo.scalaVersion}","libraryDependencies":["fixture:frozen-overloads_2.13:1.0.0"],"repositories":["$repository"]}}
              |/$caller
              |import scala.concurrent.Future
              |import fixture._
              |import fixture.Helpers._
              |object Caller {
              |  implicit val timeout: Timeout = new Timeout
              |  val content = render(Content("x"))
              |  val future = render(Future.successful(Result("y")))
              |}
              |""".stripMargin
        )
        _ <- server.didOpenAndFocus(caller)
        _ = assertNoDiagnostics()
        uri <- check("before-dependency-open")
        _ <-
          if (fullService) awaitAssociation(uri)
          else Future.successful(())
        _ <- server.didOpen(
          workspace.toNIO.relativize(uri.toAbsolutePath.toNIO).toString
        )
        _ <- check("after-dependency-open")
        _ <- check("repeated-after-dependency-open")
      } yield ()
    }
  }

  for (typeArgument <- List("String", "MissingType")) {
    test(s"dependency-overload-rebinding-rejects-compiler-type-$typeArgument") {
      cleanWorkspace()
      val repository = dependencyRepository()
      val caller = "a/src/main/scala/Caller.scala"
      for {
        _ <- initialize(
          s"""|/metals.json
              |{"a":{"scalaVersion":"${BuildInfo.scalaVersion}","libraryDependencies":["fixture:frozen-overloads_2.13:1.0.0"],"repositories":["$repository"]}}
              |/$caller
              |import scala.concurrent.Future
              |import fixture._
              |import fixture.Helpers._
              |object Caller {
              |  implicit val timeout: Timeout = new Timeout
              |  val content = render(Content("x"))
              |  val future = render(Future.successful(Result("y")))
              |}
              |""".stripMargin
        )
        _ <- server.didOpenAndFocus(caller)
        _ = assertNoDiagnostics()
        first <- server.headServer.compilers.definition(
          server.textDocumentPositionParams(caller, "ren@@der(Content(\"x\"))"),
          EmptyCancelToken,
        )
        _ = assertEquals(first.symbol, "fixture/Renderers#render().")
        dependency = first.locations.get(0).getUri.toAbsolutePath
        // Deliberately inconsistent source/binary input: the old selected method
        // has no type-equal peer. Never repair its ordinal by method name alone.
        changed = dependency.readText.replace(
          "Future[Result]",
          s"Future[$typeArgument]",
        )
        document = dependencyDocument(dependency, changed)
        _ = assert(document.symbols.nonEmpty)
        result <- server.headServer.compilers.definition(
          server.textDocumentPositionParams(
            caller,
            "ren@@der(Future.successful(Result(\"y\")))",
          ),
          EmptyCancelToken,
        )
        _ = assertEquals((result.symbol, result.querySymbol), ("", ""))
        _ = assert(result.locations.isEmpty)
      } yield ()
    }
  }

  for (openDeclarationFirst <- List(false, true)) {
    test(s"implicit-overload-open-declaration-first-$openDeclarationFirst") {
      cleanWorkspace()
      val api = "a/src/main/scala/fixture/Helpers.scala"
      val caller = "a/src/main/scala/fixture/Caller.scala"
      def check(futureFirst: Boolean): Future[Unit] = {
        val calls = List(
          (
            "Helpers.ren@@der(Content(\"x\"))",
            """|a/src/main/scala/fixture/Helpers.scala:9:7: definition
               |  def render(of: Content)(implicit timeout: Timeout): String = of.body
               |      ^^^^^^
               |""".stripMargin,
          ),
          (
            "Helpers.ren@@der(Future.successful(Result(\"y\")))",
            """|a/src/main/scala/fixture/Helpers.scala:10:7: definition
               |  def render(of: Future[Result])(implicit timeout: Timeout, codec: Codec = NoCodec): String = "future"
               |      ^^^^^^
               |""".stripMargin,
          ),
        )
        val ordered = if (futureFirst) calls.reverse else calls
        ordered.foldLeft(Future.successful(())) {
          case (previous, (query, expected)) =>
            previous.flatMap(_ =>
              server.assertDefinition(caller, query, expected).map(_ => ())
            )
        }
      }
      for {
        _ <- initialize(
          s"""|/metals.json
              |{"a": {"scalaVersion": "${BuildInfo.scalaVersion}"}}
              |/$api
              |package fixture
              |import scala.concurrent.Future
              |final case class Content(body: String)
              |final case class Result(body: String)
              |final class Timeout
              |trait Codec
              |object NoCodec extends Codec
              |trait Renderers {
              |  def render(of: Content)(implicit timeout: Timeout): String = of.body
              |  def render(of: Future[Result])(implicit timeout: Timeout, codec: Codec = NoCodec): String = "future"
              |}
              |object Helpers extends Renderers
              |/$caller
              |package fixture
              |import scala.concurrent.Future
              |object Caller {
              |  implicit val timeout: Timeout = new Timeout
              |  val content = Helpers.render(Content("x"))
              |  val future = Helpers.render(Future.successful(Result("y")))
              |}
              |""".stripMargin
        )
        _ <-
          if (openDeclarationFirst) server.didOpen(api)
          else Future.successful(())
        _ <- server.didOpenAndFocus(caller)
        _ = assertNoDiagnostics()
        _ <- check(futureFirst = false)
        _ <- check(futureFirst = true)
        _ <- check(futureFirst = false)
      } yield ()
    }
  }
}
