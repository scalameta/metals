package tests

import scala.concurrent.Future

/** Dependency-source typing must not change a cached caller's selected overload. */
class DependencyOverloadDefinitionSuite
    extends BaseLspSuite("dependency-overload-definition")
    with BaseSourcePathSuite {

  test("published-dependency-overload") {
    cleanWorkspace()
    val caller = "a/src/main/scala/Caller.scala"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{"libraryDependencies":["com.typesafe.play:play-test_2.13:2.9.5"]}}
           |/a/src/main/scala/Caller.scala
           |import scala.concurrent.Future
           |import play.api.mvc.Results.Ok
           |import play.api.test.Helpers._
           |import play.twirl.api.Html
           |object Caller {
           |  val content = contentAsString(Html("x"))
           |  val future = contentAsString(Future.successful(Ok("y")))
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(caller)
      _ = assertNoDiagnostics()
      // The first definition query types the dependency source. The cached
      // caller must still navigate to the Future overload on the next query.
      _ <- server.assertDefinition(
        caller,
        "contentAs@@String(Html(\"x\"))",
        """|.metals/readonly/dependencies/play-test_2.13-2.9.5-sources.jar/play/api/test/Helpers.scala:432:7: definition
           |  def contentAsString(of: Content)(implicit timeout: Timeout): String = of.body
           |      ^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        caller,
        "contentAs@@String(Future.successful(Ok(\"y\")))",
        """|.metals/readonly/dependencies/play-test_2.13-2.9.5-sources.jar/play/api/test/Helpers.scala:478:7: definition
           |  def contentAsString(of: Future[Result])(implicit timeout: Timeout, mat: Materializer = NoMaterializer): String =
           |      ^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // Workspace-source controls: neither declaration-opening order reproduces
  // the dependency binary/source symbol replacement.
  for (openDeclarationFirst <- List(false, true)) {
    test(s"workspace-overloads-open-declaration-first-$openDeclarationFirst") {
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
      } yield ()
    }
  }
}
