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
    "single-junit",
    List("junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3"),
    s"""|
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
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              "NoPackage",
              "NoPackage",
              "_empty_/NoPackage#",
              QuickLocation(
                classUriFor("app/src/main/scala/NoPackage.scala"),
                (1, 6, 1, 15),
              ).toLsp,
              canResolveChildren = true,
            )
          ).asJava,
        )
      )
    },
  )

  testDiscover(
    "single-munit",
    List("org.scalameta::munit:0.7.29"),
    s"""|
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
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              "a.b.c.MunitTestSuite",
              "MunitTestSuite",
              "a/b/c/MunitTestSuite#",
              QuickLocation(
                classUriFor("app/src/main/scala/a/b/c/MunitTestSuite.scala"),
                (3, 6, 3, 20),
              ).toLsp,
              canResolveChildren = true,
            )
          ).asJava,
        )
      )
    },
  )

  testDiscover(
    "multiple-suites",
    List(
      "org.scalameta::munit:0.7.29",
      "junit:junit:4.13.2",
      "com.github.sbt:junit-interface:0.13.3",
    ),
    s"""|
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
      "app/src/main/scala/another/AnotherPackage.scala",
    ),
    () => {
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              "foo.bar.FooBar",
              "FooBar",
              "foo/bar/FooBar#",
              QuickLocation(
                classUriFor("app/src/main/scala/foo/bar/FooBar.scala"),
                (2, 6, 2, 12),
              ).toLsp,
              true,
            ),
            AddTestSuite(
              "foo.Foo",
              "Foo",
              "foo/Foo#",
              QuickLocation(
                classUriFor("app/src/main/scala/foo/Foo.scala"),
                (2, 6, 2, 9),
              ).toLsp,
              true,
            ),
            AddTestSuite(
              "another.AnotherPackage",
              "AnotherPackage",
              "another/AnotherPackage#",
              QuickLocation(
                classUriFor("app/src/main/scala/another/AnotherPackage.scala"),
                (2, 6, 2, 20),
              ).toLsp,
              true,
            ),
            AddTestSuite(
              "NoPackage",
              "NoPackage",
              "_empty_/NoPackage#",
              QuickLocation(
                classUriFor("app/src/main/scala/NoPackage.scala"),
                (0, 6, 0, 15),
              ).toLsp,
              true,
            ),
          ).asJava,
        )
      )
    },
  )

  testDiscover(
    "test-cases-junit",
    List("junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3"),
    s"""|
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
        rootBuildTargetUpdate(
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
                    (3, 6, 3, 11),
                  ).toLsp,
                )
              ).asJava,
            )
          ).asJava,
        )
      )
    },
    () => Some(classUriFor("app/src/main/scala/JunitTestSuite.scala")),
  )

  testDiscover(
    "test-cases-munit",
    List("org.scalameta::munit:1.0.0-M4"),
    s"""|
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
        |  val numbers = List(1, 2, 3)
        |  numbers.foreach{
        |    n =>
        |     test(n.toShort.toString) {
        |         if (true) {
        |           val input = 123
        |           assert(input.toString.contains(n.toString))
        |         }
        |     }
        |  }
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
        rootBuildTargetUpdate(
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
                    (4, 2, 4, 6),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "test2",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (5, 2, 5, 6),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "test3",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (6, 2, 6, 6),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "check-test",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (8, 2, 8, 7),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "check-braceless",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (10, 2, 10, 16),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "check-curried",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (12, 2, 12, 14),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "tagged",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/a/b/c/MunitTestSuite.scala"
                    ),
                    (14, 2, 14, 6),
                  ).toLsp,
                ),
              ).asJava,
            )
          ).asJava,
        )
      )
    },
    () => Some(classUriFor("app/src/main/scala/a/b/c/MunitTestSuite.scala")),
  )

  testDiscover(
    "munit-no-package",
    List("org.scalameta::munit:1.0.0-M4"),
    s"""|
        |/app/src/main/scala/MunitTestSuite.scala
        |
        |class MunitTestSuite extends munit.FunSuite {
        |  test("test1") {}
        |}
        |""".stripMargin,
    List("app/src/main/scala/MunitTestSuite.scala"),
    () => {
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestCases(
              "MunitTestSuite",
              "MunitTestSuite",
              Vector(
                TestCaseEntry(
                  "test1",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/MunitTestSuite.scala"
                    ),
                    (2, 2, 2, 6),
                  ).toLsp,
                )
              ).asJava,
            )
          ).asJava,
        )
      )
    },
    () => Some(classUriFor("app/src/main/scala/MunitTestSuite.scala")),
  )

  testDiscover(
    "munit-from-parent",
    List("org.scalameta::munit:1.0.0-M4"),
    s"""|
        |/app/src/main/scala/a/BaseMunitSuite.scala
        |package a
        |trait BaseMunitSuite extends munit.FunSuite {
        |  def baseParentCheck(name: String) = test(name) {}
        |}
        |
        |/app/src/main/scala/a/b/FirstBaseMunitSuite.scala
        |package a.b
        |trait FirstBaseMunitSuite extends a.BaseMunitSuite {
        |  def firstParentCheck(name: String) = test(name) {}
        |}
        |
        |/app/src/main/scala/MunitTestSuite.scala
        |trait SecondBaseMunitSuite extends munit.FunSuite {
        |  def secondParentCheck(name: String) = test(name) {}
        |}
        |
        |class MunitTestSuite extends a.b.FirstBaseMunitSuite with SecondBaseMunitSuite {
        |  test("test-1") {}
        |  baseParentCheck("test-base")
        |  firstParentCheck("test-parent-1")
        |  secondParentCheck("test-parent-2")
        |}
        |""".stripMargin,
    List("app/src/main/scala/MunitTestSuite.scala"),
    () => {
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestCases(
              "MunitTestSuite",
              "MunitTestSuite",
              Vector(
                TestCaseEntry(
                  "test-1",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/MunitTestSuite.scala"
                    ),
                    (5, 2, 5, 6),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "test-base",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/MunitTestSuite.scala"
                    ),
                    (6, 2, 6, 17),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "test-parent-1",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/MunitTestSuite.scala"
                    ),
                    (7, 2, 7, 18),
                  ).toLsp,
                ),
                TestCaseEntry(
                  "test-parent-2",
                  QuickLocation(
                    classUriFor(
                      "app/src/main/scala/MunitTestSuite.scala"
                    ),
                    (8, 2, 8, 19),
                  ).toLsp,
                ),
              ).asJava,
            )
          ).asJava,
        )
      )
    },
    () => Some(classUriFor("app/src/main/scala/MunitTestSuite.scala")),
  )

  checkEvents(
    "check-basic-events",
    List("junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3"),
    s"""|
        |/app/src/main/scala/JunitTestSuite.scala
        |import org.junit.Test
        |class JunitTestSuite {
        |  @Test
        |  def test1 = ()
        |}
        |""".stripMargin,
    file = "app/src/main/scala/JunitTestSuite.scala",
    expected = () => {
      val fcqn = "JunitTestSuite"
      val className = "JunitTestSuite"
      val symbol = "_empty_/JunitTestSuite#"
      val file = "app/src/main/scala/JunitTestSuite.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(classUriFor(file), (1, 6, 1, 20)).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "test1",
                  QuickLocation(classUriFor(file), (3, 6, 3, 11)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  checkEvents(
    "check-backtick",
    List("junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3"),
    s"""|
        |/app/src/main/scala/JunitTestSuite.scala
        |import org.junit.Test
        |class JunitTestSuite {
        |  @Test
        |  def `test-backtick` = ()
        |}
        |""".stripMargin,
    file = "app/src/main/scala/JunitTestSuite.scala",
    expected = () => {
      val fcqn = "JunitTestSuite"
      val className = "JunitTestSuite"
      val symbol = "_empty_/JunitTestSuite#"
      val file = "app/src/main/scala/JunitTestSuite.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(classUriFor(file), (1, 6, 1, 20)).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "test$minusbacktick",
                  "test-backtick",
                  QuickLocation(classUriFor(file), (3, 6, 3, 21)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  checkEvents(
    "scalatest-any-fun-suite",
    List("org.scalatest::scalatest:3.2.13"),
    s"""|
        |/app/src/main/scala/a/FunSuite.scala
        |package a
        |import org.scalatest.funsuite.AnyFunSuite
        |import org.scalatest.Tag
        |
        |class FunSuite extends AnyFunSuite {
        |  test("An empty Set should have size 0", Tag.apply("tag")) {
        |    assert(Set.empty.size == 0)
        |  }
        |}
        |""".stripMargin,
    file = "app/src/main/scala/a/FunSuite.scala",
    expected = () => {
      val fcqn = "a.FunSuite"
      val className = "FunSuite"
      val symbol = "a/FunSuite#"
      val file = "app/src/main/scala/a/FunSuite.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(
                classUriFor(file),
                (4, 6, 4, 14),
              ).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "An empty Set should have size 0",
                  QuickLocation(classUriFor(file), (5, 2, 5, 59)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  checkEvents(
    "scalatest-any-word-spec",
    List("org.scalatest::scalatest:3.2.13"),
    s"""|
        |/app/src/main/scala/a/b/WordSpec.scala
        |package a.b
        |import org.scalatest.wordspec.AnyWordSpec
        |
        |class WordSpec extends AnyWordSpec {
        |  "A Set" when {
        |    "empty" should {
        |      "have size 0" in {
        |        assert(Set.empty.size == 0)
        |      }
        |    }
        |  }
        |}
        |""".stripMargin,
    file = "app/src/main/scala/a/b/WordSpec.scala",
    expected = () => {
      val fcqn = "a.b.WordSpec"
      val className = "WordSpec"
      val symbol = "a/b/WordSpec#"
      val file = "app/src/main/scala/a/b/WordSpec.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(
                classUriFor(file),
                (3, 6, 3, 14),
              ).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "A Set when empty should have size 0",
                  QuickLocation(classUriFor(file), (6, 6, 6, 19)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  checkEvents(
    "scalatest-any-word-spec-with-companion-object",
    List("org.scalatest::scalatest:3.2.13"),
    s"""|
        |/app/src/main/scala/a/b/WordSpec.scala
        |package a.b
        |import org.scalatest.wordspec.AnyWordSpec
        |
        |class WordSpec extends AnyWordSpec {
        |  import WordSpec._
        |  "A Set" when {
        |    "empty" should {
        |      "have size 0" in {
        |        assert(foo == "bar")
        |        assert(Set.empty.size == 0)
        |      }
        |    }
        |  }
        |}
        |
        |object WordSpec {
           def foo: String = "bar"
        |}
        |""".stripMargin,
    file = "app/src/main/scala/a/b/WordSpec.scala",
    expected = () => {
      val fcqn = "a.b.WordSpec"
      val className = "WordSpec"
      val symbol = "a/b/WordSpec#"
      val file = "app/src/main/scala/a/b/WordSpec.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(
                classUriFor(file),
                (3, 6, 3, 14),
              ).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "A Set when empty should have size 0",
                  QuickLocation(classUriFor(file), (7, 6, 7, 19)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  checkEvents(
    "scalatest-any-flat-spec",
    List("org.scalatest::scalatest:3.2.13"),
    s"""|
        |/app/src/main/scala/FlatSpec.scala
        |import org.scalatest.flatspec.AnyFlatSpec
        |
        |class FlatSpec extends AnyFlatSpec {
        |
        |  "An empty Set" should "have size 0" in {
        |    assert(Set.empty.size == 0)
        |  }
        |}
        |""".stripMargin,
    file = "app/src/main/scala/FlatSpec.scala",
    expected = () => {
      val fcqn = "FlatSpec"
      val className = "FlatSpec"
      val symbol = "_empty_/FlatSpec#"
      val file = "app/src/main/scala/FlatSpec.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(
                classUriFor(file),
                (2, 6, 2, 14),
              ).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "An empty Set should have size 0",
                  QuickLocation(classUriFor(file), (4, 2, 4, 37)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  checkEvents(
    "scalatest-any-fun-spec",
    List("org.scalatest::scalatest:3.2.13"),
    s"""|
        |/app/src/main/scala/FunSpec.scala
        |import org.scalatest.funspec.AnyFunSpec
        |
        |class FunSpec extends AnyFunSpec {
        |  describe("A Set") {
        |    describe("when empty") {
        |      it("should have size 0") {
        |        assert(Set.empty.size == 0)
        |      }
        |    }
        |  }
        |}
        |""".stripMargin,
    file = "app/src/main/scala/FunSpec.scala",
    expected = () => {
      val fcqn = "FunSpec"
      val className = "FunSpec"
      val symbol = "_empty_/FunSpec#"
      val file = "app/src/main/scala/FunSpec.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(
                classUriFor(file),
                (2, 6, 2, 13),
              ).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "A Set when empty should have size 0",
                  QuickLocation(classUriFor(file), (5, 6, 5, 30)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  checkEvents(
    "scalatest-any-free-spec",
    List("org.scalatest::scalatest:3.2.13"),
    s"""|
        |/app/src/main/scala/FreeSpec.scala
        |import org.scalatest.freespec.AnyFreeSpec
        |
        |class FreeSpec extends AnyFreeSpec {
        |  "A Set" - {
        |    "when empty" - {
        |      "should have size 0" in {
        |        assert(Set.empty.size == 0)
        |      }
        |    }
        |  }
        |}
        |""".stripMargin,
    file = "app/src/main/scala/FreeSpec.scala",
    expected = () => {
      val fcqn = "FreeSpec"
      val className = "FreeSpec"
      val symbol = "_empty_/FreeSpec#"
      val file = "app/src/main/scala/FreeSpec.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(
                classUriFor(file),
                (2, 6, 2, 14),
              ).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "A Set when empty should have size 0",
                  QuickLocation(classUriFor(file), (5, 6, 5, 26)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  checkEvents(
    "scalatest-any-prop-spec",
    List("org.scalatest::scalatest:3.2.13"),
    s"""|
        |/app/src/main/scala/PropSpec.scala
        |import org.scalatest.propspec.AnyPropSpec
        |
        |class PropSpec extends AnyPropSpec {
        |
        |  property("an empty Set should have size 0") {
        |    assert(Set.empty.size == 0)
        |  }
        |}
        |
        |""".stripMargin,
    file = "app/src/main/scala/PropSpec.scala",
    expected = () => {
      val fcqn = "PropSpec"
      val className = "PropSpec"
      val symbol = "_empty_/PropSpec#"
      val file = "app/src/main/scala/PropSpec.scala"
      List(
        rootBuildTargetUpdate(
          "app",
          targetUri,
          List[TestExplorerEvent](
            AddTestSuite(
              fcqn,
              className,
              symbol,
              QuickLocation(
                classUriFor(file),
                (2, 6, 2, 14),
              ).toLsp,
              canResolveChildren = true,
            ),
            AddTestCases(
              fcqn,
              className,
              List(
                TestCaseEntry(
                  "an empty Set should have size 0",
                  QuickLocation(classUriFor(file), (4, 2, 4, 45)).toLsp,
                )
              ).asJava,
            ),
          ).asJava,
        )
      )
    },
  )

  /**
   * Discovers all tests in project or test cases in file
   *
   * @param dependencies list of dependencies, e.g. "org.scalameta::munit:0.7.29"
   * @param files list of files for which compilation will be triggered. It's
   * @param expected list of expected build target updates
   * @param uri URI of file for which test cases should be discovered
   */
  def testDiscover(
      name: TestOptions,
      dependencies: List[String],
      layout: String,
      files: List[String],
      expected: () => List[BuildTargetUpdate],
      uri: () => Option[String] = () => None,
  )(implicit
      loc: munit.Location
  ): Unit = {
    val layoutWithDependencies =
      s"""|/metals.json
          |{
          |  "app": {
          |    "libraryDependencies" : ${getDependenciesArray(dependencies)},
          |    "scalaVersion": "${BuildInfo.scalaVersion}"
          |  }
          |}
          |$layout
          |""".stripMargin
    test(name) {
      for {
        _ <- Future.successful(cleanWorkspace())
        _ <- initialize(layoutWithDependencies)
        discovered <- server.discoverTestSuites(files, uri())
      } yield {
        assertEquals(discovered, expected())
      }
    }
  }

  /**
   * Check if Test Explorer returns particular events.
   *
   * @param dependencies list of dependencies, e.g. "org.scalameta::munit:0.7.29"
   * @param file which should be compiled
   * @param expected list of expected build target updates
   */
  def checkEvents(
      name: TestOptions,
      dependencies: List[String],
      layout: String,
      file: String,
      expected: () => List[BuildTargetUpdate],
  )(implicit
      loc: munit.Location
  ): Unit = {
    val layoutWithDependencies =
      s"""|/metals.json
          |{
          |  "app": {
          |    "libraryDependencies" : ${getDependenciesArray(dependencies)},
          |    "scalaVersion": "${BuildInfo.scalaVersion}"
          |  }
          |}
          |$layout
          |""".stripMargin
    test(name) {
      for {
        _ <- Future.successful(cleanWorkspace())
        _ <- initialize(layoutWithDependencies)
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

  private def getDependenciesArray(dependencies: List[String]): String = {
    val commaSeparated = dependencies.map(d => s"\"$d\"").mkString(", ")
    s"[ $commaSeparated ]"
  }

  private def targetUri: String =
    s"${workspace.toURI.toString}app/?id=app".replace("///", "/")

  private def classUriFor(relativePath: String): String =
    workspace.resolve(relativePath).toURI.toString

  private def rootBuildTargetUpdate(
      targetName: String,
      targetUri: String,
      events: java.util.List[TestExplorerEvent],
  ): BuildTargetUpdate =
    BuildTargetUpdate(
      targetName,
      targetUri,
      "root",
      server.server.folder.toNIO.toString,
      events,
    )
}
