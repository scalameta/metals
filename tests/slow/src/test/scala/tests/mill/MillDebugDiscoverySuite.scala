package tests.mill

import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.BuildInfo.scala3
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTestSuiteSelection
import scala.meta.internal.metals.ScalaTestSuites
import scala.meta.internal.metals.debug.JUnit4
import scala.meta.internal.metals.debug.Scalatest

import ch.epfl.scala.bsp4j.TestParamsDataKind
import tests.BaseDapSuite
import tests.MillBuildLayout
import tests.MillServerInitializer

class MillDebugDiscoverySuite
    extends BaseDapSuite(
      "mill-debug-discovery",
      MillServerInitializer,
      MillBuildLayout,
    ) {

  private val fooPath = "a/test/src/Foo.scala"
  private val barPath = "a/test/src/Bar.scala"

  for (scala <- List(scalaVersion, scala3)) {

    test(s"testTarget-$scala") {
      cleanWorkspace()
      for {
        _ <- initialize(
          MillBuildLayout(
            s"""
               |/${fooPath}
               |package a
               |class Foo extends org.scalatest.funsuite.AnyFunSuite {
               |  test("foo") {}
               |}
               |/${barPath}
               |package a
               |class Bar extends org.scalatest.funsuite.AnyFunSuite {
               |  test("bart") {}
               |}
               |""".stripMargin,
            scala,
            Some(Scalatest),
          )
        )
        _ <- server.didOpen(barPath)
        _ <- server.didSave(barPath)(identity)
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
            Some(JUnit4),
          )
        )
        _ <- server.didOpen(fooPath)
        _ <- server.didSave(fooPath)(identity)
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
          Some(Scalatest),
        )
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)(identity)
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
}
