package tests.testProvider

import scala.concurrent.Future

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.testProvider.BuildTargetUpdate
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.metals.testProvider.TestExplorerEvent
import scala.meta.internal.metals.testProvider.TestExplorerEvent._

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import munit.TestOptions
import tests.BaseLspSuite
import tests.BuildInfo
import tests.QuickLocation

class TestSuitesProviderSuite extends BaseLspSuite("testSuitesFinderSuite") {
  override protected def initializationOptions: Some[InitializationOptions] =
    Some(
      InitializationOptions.Default.copy(testExplorerProvider = Some(true))
    )

  override val userConfig: UserConfiguration =
    UserConfiguration(testUserInterface = TestUserInterfaceKind.TestExplorer)

  val gson: Gson =
    new GsonBuilder().setPrettyPrinting.disableHtmlEscaping().create()

  testDiscover(
    "discover-single-junit",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3" ],
        |    "scalaVersion": "${BuildInfo.scalaVersion}"
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
              "NoPackage",
              "NoPackage",
              "_empty_/NoPackage#",
              QuickLocation(
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
    "discover-single-munit",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "org.scalameta::munit:0.7.29" ],
        |    "scalaVersion": "${BuildInfo.scalaVersion}"
        |  }
        |}
        |
        |/app/src/main/scala/a/b/c/MunitTestSuite.scala
        |package a.b
        |package c
        |
        |class MunitTestSuite extends munit.FunSuite {
        |  test("test1") {
        |  }
        |}
        |""".stripMargin,
    List("app/src/main/scala/a/b/c/MunitTestSuite.scala"),
    () => {
      List(
        BuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              "a.b.c.MunitTestSuite",
              "MunitTestSuite",
              "a/b/c/MunitTestSuite#",
              QuickLocation(
                classUriFor("app/src/main/scala/a/b/c/MunitTestSuite.scala"),
                (3, 6, 3, 20)
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
        |    "scalaVersion": "${BuildInfo.scalaVersion}"
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
              "foo.bar.FooBar",
              "FooBar",
              "foo/bar/FooBar#",
              QuickLocation(
                classUriFor("app/src/main/scala/foo/bar/FooBar.scala"),
                (2, 6, 2, 12)
              ).toLsp,
              true
            ),
            AddTestSuite(
              "foo.Foo",
              "Foo",
              "foo/Foo#",
              QuickLocation(
                classUriFor("app/src/main/scala/foo/Foo.scala"),
                (2, 6, 2, 9)
              ).toLsp,
              true
            ),
            AddTestSuite(
              "another.AnotherPackage",
              "AnotherPackage",
              "another/AnotherPackage#",
              QuickLocation(
                classUriFor("app/src/main/scala/another/AnotherPackage.scala"),
                (2, 6, 2, 20)
              ).toLsp,
              true
            ),
            AddTestSuite(
              "NoPackage",
              "NoPackage",
              "_empty_/NoPackage#",
              QuickLocation(
                classUriFor("app/src/main/scala/NoPackage.scala"),
                (0, 6, 0, 15)
              ).toLsp,
              true
            )
          ).asJava
        )
      )
    }
  )

  testDiscover(
    "discover-test-cases-junit",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3" ],
        |    "scalaVersion": "${BuildInfo.scalaVersion}"
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
              "JunitTestSuite",
              "JunitTestSuite",
              List(
                TestCaseEntry(
                  "test1",
                  QuickLocation(
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

  testDiscover(
    "discover-test-cases-munit",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : ["org.scalameta::munit:1.0.0-M3" ],
        |    "scalaVersion": "${BuildInfo.scalaVersion}"
        |  }
        |}
        |
        |/app/src/main/scala/a/b/c/MunitTestSuite.scala
        |package a.b
        |package c
        |
        |class MunitTestSuite extends munit.FunSuite {
        |  test("test1") {}
        |  test("test2".ignore) {}
        |  test("test3".only) {}
        |
        |  check("check-test", 2, 2)
        |
        |  checkBraceless("check-braceless", 2, 2)
        |
        |  checkCurried("check-curried")(2, 2)
        |
        |  test("tagged".tag(new munit.Tag("tag"))) {}
        |
        |  List("") // negative case - apply without test call
        |
        |  def check(name: String, n1: Int, n2: Int = 1) = {
        |    test(name) {
        |      assertEquals(n1, n2)
        |    }
        |  }
        |
        |  def checkBraceless(name: String, n1: Int, n2: Int = 1) =
        |    test(name) {
        |      println(name)
        |      assertEquals(n1, n2)
        |    }
        |
        |  def checkCurried(name: String)(n1: Int, n2: Int = 1) =
        |    test(name) {
        |      println(name)
        |      assertEquals(n1, n2)
        |    }
        |}
        |""".stripMargin,
    List("app/src/main/scala/a/b/c/MunitTestSuite.scala"),
    () => {
      List(
        BuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestCases(
              "a.b.c.MunitTestSuite",
              "MunitTestSuite",
              Vector(
                TestCaseEntry(
                  "test1",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (4, 2, 4, 6)
                  ).toLsp
                ),
                TestCaseEntry(
                  "test2",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (5, 2, 5, 6)
                  ).toLsp
                ),
                TestCaseEntry(
                  "test3",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (6, 2, 6, 6)
                  ).toLsp
                ),
                TestCaseEntry(
                  "check-test",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (8, 2, 8, 7)
                  ).toLsp
                ),
                TestCaseEntry(
                  "check-braceless",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (10, 2, 10, 16)
                  ).toLsp
                ),
                TestCaseEntry(
                  "check-curried",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (12, 2, 12, 14)
                  ).toLsp
                ),
                TestCaseEntry(
                  "tagged",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (14, 2, 14, 6)
                  ).toLsp
                )
              ).asJava
            )
          ).asJava
        )
      )
    },
    () => Some(classUriFor("app/src/main/scala/a/b/c/MunitTestSuite.scala"))
  )

  checkEvents(
    "check-events",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3" ],
        |    "scalaVersion": "${BuildInfo.scalaVersion}"
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
      val fcqn = "JunitTestSuite"
      val className = "JunitTestSuite"
      val symbol = "_empty_/JunitTestSuite#"
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
              QuickLocation(classUriFor(file), (1, 6, 1, 20)).toLsp,
              canResolveChildren = true
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "test1",
                  QuickLocation(classUriFor(file), (3, 6, 3, 11)).toLsp
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
        assertEquals(discovered, expected())
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
        jsonObjects <- server.client.testExplorerUpdates.future
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
