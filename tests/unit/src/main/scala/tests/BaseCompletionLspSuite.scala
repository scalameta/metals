package tests

import org.eclipse.lsp4j.CompletionList
import scala.concurrent.Future
import scala.meta.internal.metals.TextEdits
import munit.Location

abstract class BaseCompletionLspSuite(name: String) extends BaseLspSuite(name) {

  def withCompletion(query: String, project: Char = 'a')(
      fn: CompletionList => Unit
  )(implicit loc: Location): Future[Unit] = {
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
  )(implicit loc: Location): Future[Unit] = {
    withCompletion(query, project) { list =>
      val completion = server.formatCompletion(list, includeDetail, filter)
      assertNoDiff(completion, expected)
    }
  }

  def withCompletionEdit(
      query: String,
      project: Char = 'a',
      filter: String => Boolean = _ => true
  )(
      fn: String => Unit
  ): Future[Unit] = {
    import scala.collection.JavaConverters._
    val filename = s"$project/src/main/scala/$project/${project.toUpper}.scala"
    val text = server
      .textContentsOnDisk(filename)
      .replaceAllLiterally("// @@", query.replaceAllLiterally("@@", ""))
    for {
      _ <- server.didChange(filename)(_ => text)
      completion <- server.completionList(filename, query)
    } yield {
      val items =
        completion.getItems().asScala.filter(item => filter(item.getLabel))
      val obtained = TextEdits.applyEdits(text, items.head)
      fn(obtained)
    }
  }

  def assertCompletionEdit(
      query: String,
      expected: String,
      project: Char = 'a',
      filter: String => Boolean = _ => true
  )(implicit loc: Location): Future[Unit] = {
    withCompletionEdit(query, project, filter) { obtained =>
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

  def matchKeywordTest(scalaVersion: String): Future[Unit] = {
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
           |  val x: Option[Int] = Some(1)
           |  // @@
           |}
           |/a/src/main/scala/a/Color.scala
           |package a
           |abstract sealed class Color
           |case object Red extends Color
           |case object Blue extends Color
           |case object Green extends Color
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      // completed exhausted matches should be sorted by declaration order
      // https://github.com/scala/scala/blob/cca78e1e18c55e5b0223b9dfa4ac230f7bc6a858/src/library/scala/Option.scala#L513-L527
      _ <- assertCompletionEdit(
        "x matc@@",
        """|package a
           |object A {
           |  val x: Option[Int] = Some(1)
           |  x match {
           |\tcase Some(value) =>
           |\tcase None =>
           |}
           |}
           |""".stripMargin,
        filter = _.contains("exhaustive")
      )
      _ <- assertCompletionEdit(
        "null.asInstanceOf[Color] matc@@",
        """|package a
           |object A {
           |  val x: Option[Int] = Some(1)
           |  null.asInstanceOf[Color] match {
           |\tcase Red =>
           |\tcase Blue =>
           |\tcase Green =>
           |}
           |}
           |""".stripMargin,
        filter = _.contains("exhaustive")
      )
    } yield ()
  }
}
