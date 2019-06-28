package tests

import org.eclipse.lsp4j.CompletionList
import scala.concurrent.Future

abstract class BaseCompletionSlowSuite(name: String)
    extends BaseSlowSuite(name) {

  def withCompletion(query: String, project: Char = 'a')(
      fn: CompletionList => Unit
  )(implicit file: sourcecode.File, line: sourcecode.Line): Future[Unit] = {
    val filename = s"$project/src/main/scala/$project/${project.toUpper}.scala"
    val text = server
      .textContentsOnDisk(filename)
      .replaceAllLiterally("// @@", query.replaceAllLiterally("@@", ""))
    for {
      _ <- server.didChange(filename)(_ => text)
      completion <- server.completionList(filename, query)
    } yield {
      fn(completion)
    }
  }

  def assertCompletion(
      query: String,
      expected: String,
      project: Char = 'a',
      includeDetail: Boolean = true,
      filter: String => Boolean = _ => true
  )(implicit file: sourcecode.File, line: sourcecode.Line): Future[Unit] = {
    withCompletion(query, project) { list =>
      val completion = server.formatCompletion(list, includeDetail, filter)
      val obtained =
        if (isWindows) {
          // HACK(olafur) we don't have access to the JDK sources on Appveyor
          // and the completion tests assert against the signatures of String.substring
          // which has parameters `beginIndex` and `endIndex`. This hack can be removed
          // if we figure out how to access JDK sources on Appveyor.
          completion
            .replaceAllLiterally("x$1", "beginIndex")
            .replaceAllLiterally("x$2", "endIndex")
        } else {
          completion
        }
      assertNoDiff(obtained, expected)
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
           |  // @@
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "\"\".substrin@@",
        """|substring(beginIndex: Int): String
           |substring(beginIndex: Int, endIndex: Int): String
           |""".stripMargin
      )
      _ <- assertCompletion(
        "Stream@@",
        getExpected(
          """|Stream scala.collection.immutable
             |Stream - java.util.stream
             |IntStream - java.util.stream
             |LogStream - java.rmi.server
             |StreamView - scala.collection.immutable
             |Streamable - scala.reflect.io
             |BaseStream - java.util.stream
             |LongStream - java.util.stream
             |InputStream - java.io
             |PrintStream - java.io
             |DoubleStream - java.util.stream
             |OutputStream - java.io
             |StreamBuilder - scala.collection.immutable.Stream
             |StreamCanBuildFrom - scala.collection.immutable.Stream
             |""".stripMargin,
          Map(
            "2.13" ->
              """|Stream scala.collection.immutable
                 |Stream - java.util.stream
                 |IntStream - java.util.stream
                 |LogStream - java.rmi.server
                 |Streamable - scala.reflect.io
                 |BaseStream - java.util.stream
                 |LongStream - java.util.stream
                 |StreamShape - scala.collection.convert.StreamExtensions
                 |InputStream - java.io
                 |PrintStream - java.io
                 |DoubleStream - java.util.stream
                 |OutputStream - java.io
                 |""".stripMargin
          ),
          scalaVersion
        )
      )
      _ <- assertCompletion(
        "TrieMap@@",
        getExpected(
          """|TrieMap - scala.collection.concurrent
             |ParTrieMap - scala.collection.parallel.mutable
             |HashTrieMap - scala.collection.immutable.HashMap
             |ParTrieMapCombiner - scala.collection.parallel.mutable
             |ParTrieMapSplitter - scala.collection.parallel.mutable
             |TrieMapSerializationEnd - scala.collection.concurrent
             |""".stripMargin,
          Map(
            "2.13" ->
              """|TrieMap - scala.collection.concurrent
                 |TrieMapSerializationEnd - scala.collection.concurrent
                 |""".stripMargin
          ),
          scalaVersion
        )
      )
      _ <- assertCompletion(
        """
          |locally {
          |  val myLocalVariable = Array("")
          |  myLocalVariable@@
          |  val source = ""
          |}
          |""".stripMargin,
        """|myLocalVariable: Array[String]
           |""".stripMargin
      )
    } yield ()
  }
}
