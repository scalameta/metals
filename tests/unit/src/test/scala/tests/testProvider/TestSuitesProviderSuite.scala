package tests

import scala.concurrent.Future

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.testProvider.BuildTargetUpdate
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.metals.testProvider.TestExplorerEvent
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.mtags

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import munit.TestOptions
import org.eclipse.{lsp4j => l}

class TestSuitesProviderSuite extends BaseLspSuite("testSuitesFinderSuite") {
  override def initializationOptions: Some[InitializationOptions] = Some(
    InitializationOptions.Default.copy(testExplorerProvider = Some(true))
  )

  val gson: Gson =
    new GsonBuilder().setPrettyPrinting.disableHtmlEscaping().create()

  testDiscover(
    "discover-single-junit",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3" ],
        |    "scalaVersion": "2.13.6"
        |  }
        |}
        |
        |/app/src/main/scala/NoPackage.scala
        |import org.junit.Test
        |class NoPackage {
        |  @Test
        |  def test1 = ()
        |}
        |""".stripMargin,
    List(
      "app/src/main/scala/NoPackage.scala"
    ),
    () => {
      List(
        BuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              FullyQualifiedName("NoPackage"),
              ClassName("NoPackage"),
              mtags.Symbol("_empty_/NoPackage#"),
              Location(
                classUriFor("app/src/main/scala/NoPackage.scala"),
                (1, 6, 1, 15)
              ).toLsp,
              canResolveChildren = true
            )
          ).asJava
        )
      )
    }
  )

  testDiscover(
    "discover-multiple-suites",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "org.scalameta::munit:0.7.29", "junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3"],
        |    "scalaVersion": "2.13.6"
        |  }
        |}
        |
        |/app/src/main/scala/NoPackage.scala
        |class NoPackage extends munit.FunSuite {
        |  test("noPackage") {}
        |}
        |
        |/app/src/main/scala/foo/Foo.scala
        |package foo
        |
        |class Foo extends munit.FunSuite {
        |  test("Foo") {}
        |}
        |
        |/app/src/main/scala/foo/bar/FooBar.scala
        |package foo.bar
        |
        |class FooBar extends munit.FunSuite {
        |  test("FooBar") {}
        |}
        |
        |/app/src/main/scala/another/AnotherPackage.scala
        |package another
        |
        |class AnotherPackage extends munit.FunSuite {
        |  test("AnotherPackage") {}
        |}
        |
        |""".stripMargin,
    List(
      "app/src/main/scala/NoPackage.scala",
      "app/src/main/scala/foo/Foo.scala",
      "app/src/main/scala/foo/bar/FooBar.scala",
      "app/src/main/scala/another/AnotherPackage.scala"
    ),
    () => {
      List(
        BuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              FullyQualifiedName("NoPackage"),
              ClassName("NoPackage"),
              mtags.Symbol("_empty_/NoPackage#"),
              Location(
                classUriFor("app/src/main/scala/NoPackage.scala"),
                (0, 6, 0, 15)
              ).toLsp
            ),
            AddTestSuite(
              FullyQualifiedName("another.AnotherPackage"),
              ClassName("AnotherPackage"),
              mtags.Symbol("another/AnotherPackage#"),
              Location(
                classUriFor("app/src/main/scala/another/AnotherPackage.scala"),
                (2, 6, 2, 20)
              ).toLsp
            ),
            AddTestSuite(
              FullyQualifiedName("foo.Foo"),
              ClassName("Foo"),
              mtags.Symbol("foo/Foo#"),
              Location(
                classUriFor("app/src/main/scala/foo/Foo.scala"),
                (2, 6, 2, 9)
              ).toLsp
            ),
            AddTestSuite(
              FullyQualifiedName("foo.bar.FooBar"),
              ClassName("FooBar"),
              mtags.Symbol("foo/bar/FooBar#"),
              Location(
                classUriFor("app/src/main/scala/foo/bar/FooBar.scala"),
                (2, 6, 2, 12)
              ).toLsp
            )
          ).asJava
        )
      )
    }
  )

  testDiscover(
    "discover-test-cases",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3" ],
        |    "scalaVersion": "2.13.6"
        |  }
        |}
        |
        |/app/src/main/scala/JunitTestSuite.scala
        |import org.junit.Test
        |class JunitTestSuite {
        |  @Test
        |  def test1 = ()
        |}
        |""".stripMargin,
    List("app/src/main/scala/JunitTestSuite.scala"),
    () => {
      List(
        BuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestCases(
              FullyQualifiedName("JunitTestSuite"),
              ClassName("JunitTestSuite"),
              List(
                TestCaseEntry(
                  "test1",
                  Location(
                    classUriFor("app/src/main/scala/JunitTestSuite.scala"),
                    (3, 6, 3, 11)
                  ).toLsp
                )
              ).asJava
            )
          ).asJava
        )
      )
    },
    () => Some(classUriFor("app/src/main/scala/JunitTestSuite.scala"))
  )

  checkEvents(
    "check-events",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3" ],
        |    "scalaVersion": "2.13.6"
        |  }
        |}
        |
        |/app/src/main/scala/JunitTestSuite.scala
        |import org.junit.Test
        |class JunitTestSuite {
        |  @Test
        |  def test1 = ()
        |}
        |""".stripMargin,
    "app/src/main/scala/JunitTestSuite.scala",
    () => {
      val fcqn = FullyQualifiedName("JunitTestSuite")
      val className = ClassName("JunitTestSuite")
      val symbol = mtags.Symbol("_empty_/JunitTestSuite#")
      val file = "app/src/main/scala/JunitTestSuite.scala"
      List(
        BuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              Location(classUriFor(file), (1, 6, 1, 20)).toLsp,
              canResolveChildren = true
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "test1",
                  Location(classUriFor(file), (3, 6, 3, 11)).toLsp
                )
              ).asJava
            )
          ).asJava
        )
      )
    }
  )

  def testDiscover(
      name: TestOptions,
      layout: String,
      files: List[String],
      expected: () => List[BuildTargetUpdate],
      uri: () => Option[String] = () => None
  )(implicit
      loc: munit.Location
  ) {
    test(name) {
      for {
        _ <- Future.successful(cleanWorkspace())
        _ <- initialize(layout)
        discovered <- server.discoverTestSuites(files, uri())
      } yield {
        val prettyPrinted = discovered.map(gson.toJson)
        val prettyPrintedExpected = expected().map(gson.toJson)

        assertEquals(prettyPrinted, prettyPrintedExpected)
      }
    }
  }

  def checkEvents(
      name: TestOptions,
      layout: String,
      file: String,
      expected: () => List[BuildTargetUpdate]
  )(implicit
      loc: munit.Location
  ) {
    test(name) {
      for {
        _ <- Future.successful(cleanWorkspace())
        _ <- initialize(layout)
        _ <- server.didOpen(file)
        _ <- server.executeCommand(ServerCommands.CascadeCompile)
        jsonObjects <- server.client.updateTestExplorer.future
      } yield {
        val prettyPrinted = jsonObjects.map(gson.toJson)
        val prettyPrintedExpected = expected().map(gson.toJson)

        assertEquals(prettyPrinted, prettyPrintedExpected)
      }
    }
  }

  private def targetUri: String =
    s"${workspace.toURI.toString}app/?id=app".replace("///", "/")

  private def classUriFor(relativePath: String): String =
    workspace.resolve(relativePath).toURI.toString

}

private final case class Location(
    uri: String,
    range: (Int, Int, Int, Int)
) {
  def toLsp: l.Location = new l.Location(
    uri,
    new l.Range(
      new l.Position(range._1, range._2),
      new l.Position(range._3, range._4)
    )
  )
}
