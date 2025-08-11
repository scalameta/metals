package tests

import scala.meta.internal.metals.{BuildInfo => V}

import munit.TestOptions

class ResolveStacktraceLocationLspSuite
    extends BaseAnalyzeStacktraceSuite("resolve-stacktrace-location") {

  def checkLocation(
      name: TestOptions,
      code: String,
      stacktraceLine: String,
      expectedLine: Int,
      filename: String = "Main.scala",
      scalaVersion: String = V.scala213,
      dependency: String = "",
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{ 
             |  "a": { 
             |    "scalaVersion": "$scalaVersion",
             |    "libraryDependencies": [ $dependency ]
             |   }
             |}
             |/a/src/main/scala/a/$filename
             |$code
             |""".stripMargin
        )
        // Don't call server.didOpen - just initialize the workspace
        location = server.resolveStacktraceLocation(stacktraceLine)
        _ = {
          location match {
            case Some(loc) =>
              val actualLine = loc.getRange().getStart().getLine()
              assertEquals(
                actualLine,
                expectedLine,
                s"Expected line $expectedLine but got $actualLine",
              )
              assert(
                loc.getUri().contains(filename),
                s"Expected URI to contain $filename but got ${loc.getUri()}",
              )
            case None =>
              fail(
                s"Expected location but got None for stacktrace line: $stacktraceLine"
              )
          }
        }
      } yield ()
    }
  }

  def checkNone(
      name: TestOptions,
      stacktraceLine: String,
      code: String = defaultCode,
      filename: String = "Main.scala",
      scalaVersion: String = V.scala213,
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{ 
             |  "a": { 
             |    "scalaVersion": "$scalaVersion"
             |   }
             |}
             |/a/src/main/scala/a/$filename
             |$code
             |""".stripMargin
        )
        location = server.resolveStacktraceLocation(stacktraceLine)
        _ = assertEquals(
          location,
          None,
          s"Expected None but got $location for stacktrace line: $stacktraceLine",
        )
      } yield ()
    }
  }

  private lazy val defaultCode: String =
    """|package a.b
       |
       |object Main {
       |  def main(args: Array[String]): Unit = {
       |    new ClassError().raise
       |  }
       |}
       |
       |class ClassError {
       |  def raise: ClassConstrError = {
       |    ObjectError.raise
       |  }
       |}
       |
       |object ObjectError {
       |  def raise: ClassConstrError = {
       |    new ClassConstrError()
       |  }
       |}
       |
       |class ClassConstrError {
       |  val a = 3
       |  throw new Exception("error")
       |  val b = 4
       |}
       |""".stripMargin

  checkLocation(
    "simple-method-call",
    defaultCode,
    "\tat a.b.ClassError.raise(Main.scala:11)",
    expectedLine = 10, // 0-indexed, so line 11 in stacktrace = line 10 in LSP
  )

  checkLocation(
    "object-method-call",
    defaultCode,
    "\tat a.b.ObjectError$.raise(Main.scala:16)",
    expectedLine = 15,
  )

  checkLocation(
    "constructor-error",
    defaultCode,
    "\tat a.b.ClassConstrError.<init>(Main.scala:23)",
    expectedLine = 22,
  )

  checkNone(
    "invalid-stacktrace-line",
    "invalid stacktrace line format",
  )

  checkNone(
    "external-library-location",
    "\tat java.lang.Thread.run(Thread.java:748)",
  )

  checkNone(
    "non-existent-method",
    "\tat a.b.NonExistent.method(Main.scala:5)",
  )
}
