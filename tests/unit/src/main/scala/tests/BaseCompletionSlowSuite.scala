package tests

import scala.concurrent.Future

abstract class BaseCompletionSlowSuite(name: String)
    extends BaseSlowSuite(name) {
  def assertCompletion(
      query: String,
      expected: String,
      project: Char = 'a'
  )(implicit file: sourcecode.File, line: sourcecode.Line): Future[Unit] = {
    val filename = s"$project/src/main/scala/$project/${project.toUpper}.scala"
    server.completion(filename, query).map { completion =>
      assertNoDiff(completion, expected)
    }
  }

  def basicTest(scalaVersion: String): Future[Unit] = {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": { "scalaVersion": "${scalaVersion}" }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |object A {
           |  val x = "".substrin
           |  Stream
           |  TrieMap
           |  locally {
           |    val myLocalVariable = Array("")
           |    myLocalVariable
           |    val source = ""
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNotEmpty(client.workspaceDiagnostics)
      _ <- assertCompletion(
        "substrin@@",
        """|substring(beginIndex: Int): String
           |substring(beginIndex: Int, endIndex: Int): String
           |""".stripMargin
      )
      _ <- assertCompletion(
        "Stream@@",
        """|Stream scala.collection.immutable
           |Stream java.util.stream
           |IntStream java.util.stream
           |LogStream java.rmi.server
           |BaseStream java.util.stream
           |LongStream java.util.stream
           |StreamView scala.collection.immutable
           |InputStream java.io
           |PrintStream java.io
           |DoubleStream java.util.stream
           |OutputStream java.io
           |StreamBuilder scala.collection.immutable.Stream
           |StreamHandler java.util.logging
           |StreamCanBuildFrom scala.collection.immutable.Stream
           |""".stripMargin
      )
      _ <- assertCompletion(
        "TrieMap@@",
        """|TrieMap scala.collection.concurrent
           |ParTrieMap scala.collection.parallel.mutable
           |HashTrieMap scala.collection.immutable.HashMap
           |ParTrieMapCombiner scala.collection.parallel.mutable
           |ParTrieMapSplitter scala.collection.parallel.mutable
           |TrieMapSerializationEnd scala.collection.concurrent
           |""".stripMargin
      )
      _ <- assertCompletion(
        "  myLocalVariable@@",
        """|myLocalVariable: Array[String]
           |""".stripMargin
      )
    } yield ()
  }
}
