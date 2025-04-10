package tests.mill

import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTestSuiteSelection
import scala.meta.internal.metals.ScalaTestSuites

import bloop.config.Config.TestFramework
import ch.epfl.scala.bsp4j.TestParamsDataKind
import tests.BaseDapSuite
import tests.BaseMillServerSuite
import tests.MillBuildLayout
import tests.MillServerInitializer

class MillDebugDiscoverySuite
    extends BaseDapSuite(
      "mill-debug-discovery",
      MillServerInitializer,
      MillBuildLayout,
    )
    with BaseMillServerSuite {

  private val fooPath = "a/test/src/Foo.scala"
  private val barPath = "a/test/src/Bar.scala"

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    killMillServer(workspace)
  }

  // mill sometimes hangs and doesn't return main classes
  override protected val retryTimes: Int = 2

  for (scala <- List(scalaVersion, BuildInfo.scala3)) {

    test(s"testTarget-$scala") {
      cleanWorkspace()
      val message = "Hello"
      for {
        _ <- initialize(
          MillBuildLayout(
            s"""
               |/a/src/Main.scala
               |package a
               |object Main {
               |  def message = "$message"
               |}
               |/${fooPath}
               |package a
               |class Foo extends org.scalatest.funsuite.AnyFunSuite {
               |  test("foo") {}
               |}
               |/${barPath}
               |package a
               |class Bar extends org.scalatest.funsuite.AnyFunSuite {
               |  test("bart") {
               |    assert(Main.message == "$message")
               |  }
               |}
               |""".stripMargin,
            scala,
            Some(TestFramework.ScalaTest),
          )
        )
        _ <- server.didOpen("a/src/Main.scala")
        _ <- server.didSave(
          "a/src/Main.scala"
        ) // making sure it gets compiled to the correct destination
        _ <- server.didOpen(barPath)
        _ <- server.didSave(barPath)
        _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
        debugger <- server.startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(barPath).toURI.toString,
            "testTarget",
          ).toJson
        )
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
        output <- debugger.allOutput
        _ = server.server.cancel()
      } yield assert(output.contains("All tests in a.Bar passed"))
    }

    test(s"junit-$scala") {
      cleanWorkspace()
      for {
        _ <- initialize(
          MillBuildLayout(
            s"""|/${fooPath}
                |package a
                |import org.junit.Test
                |import org.junit.Assert._
                |
                |class Foo {
                |  @Test
                |  def testOneIsPositive = {
                |    assertTrue(1 > 0)
                |  }
                |
                |  @Test
                |  def testMinusOneIsNegative = {
                |    assertTrue(-1 < 0)
                |  }
                |}
                |""".stripMargin,
            scala,
            Some(TestFramework.JUnit),
          )
        )
        _ <- server.didOpen(fooPath)
        _ <- server.didSave(fooPath)
        _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
        debugger <- server.startDebugging(
          "a.test",
          TestParamsDataKind.SCALA_TEST_SUITES_SELECTION,
          ScalaTestSuites(
            List(
              ScalaTestSuiteSelection("a.Foo", Nil.asJava)
            ).asJava,
            Nil.asJava,
            Nil.asJava,
          ),
        )
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
        output <- debugger.allOutput
        _ = server.server.cancel()
      } yield assert(output.contains("All tests in a.Foo passed"))
    }
  }

  test(s"test-selection") {
    cleanWorkspace()
    for {
      _ <- initialize(
        MillBuildLayout(
          s"""
             |/${fooPath}
             |package a
             |class Foo extends org.scalatest.funsuite.AnyFunSuite {
             |  test("foo") {}
             |  test("bar") {}
             |}
             |""".stripMargin,
          scalaVersion,
          Some(TestFramework.ScalaTest),
        )
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebugging(
        "a.test",
        TestParamsDataKind.SCALA_TEST_SUITES_SELECTION,
        ScalaTestSuites(
          List(
            ScalaTestSuiteSelection("a.Foo", List("foo").asJava)
          ).asJava,
          Nil.asJava,
          Nil.asJava,
        ),
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
      _ = server.server.cancel()
    } yield assertNoDiff(
      output.replaceFirst("[0-9]+ms", "xxx"),
      """|Foo:
         |- foo
         |Execution took xxx
         |1 tests, 1 passed
         |All tests in a.Foo passed
         |""".stripMargin,
    )
  }

  test("assert-location") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- initialize(
        MillBuildLayout(
          s"""
             |/${fooPath}
             |package a
             |class Foo extends org.scalatest.funsuite.AnyFunSuite {
             |  test("foo") {
             |    println("foo")
             |    println("foo")
             |    println("foo")
             |    assert(1 == 2)
             |  }
             |}
             |""".stripMargin,
          scalaVersion,
          Some(TestFramework.ScalaTest),
        )
      )
      debugger <-
        server
          .startDebuggingUnresolved(
            new DebugUnresolvedTestClassParams(
              "a.Foo"
            ).toJson
          )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allTestEvents
    } yield assertNoDiff(
      output,
      """|a.Foo
         |  foo - failed at Foo.scala, line 6
         |""".stripMargin,
    )
  }
}
