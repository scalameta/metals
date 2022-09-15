package tests

import scala.concurrent.Future

import scala.meta.internal.metals.TextEdits

import munit.Location
import org.eclipse.lsp4j.CompletionList

abstract class BaseCompletionLspSuite(name: String) extends BaseLspSuite(name) {

  def withCompletion(query: String, project: Char = 'a')(
      fn: CompletionList => Unit
  ): Future[Unit] = {
    val filename = s"$project/src/main/scala/$project/${project.toUpper}.scala"
    val text = server
      .textContentsOnDisk(filename)
      .replace("// @@", query.replace("@@", ""))
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
      filter: String => Boolean = _ => true,
  )(implicit loc: Location): Future[Unit] = {
    withCompletion(query, project) { list =>
      val completion = server.formatCompletion(list, includeDetail, filter)
      assertNoDiff(completion, expected)
    }
  }

  def withCompletionEdit(
      query: String,
      project: Char = 'a',
      filter: String => Boolean = _ => true,
  )(
      fn: String => Unit
  ): Future[Unit] = {
    import scala.collection.JavaConverters._
    val filename = s"$project/src/main/scala/$project/${project.toUpper}.scala"
    val text = server
      .textContentsOnDisk(filename)
      .replace("// @@", query.replace("@@", ""))
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
      filter: String => Boolean = _ => true,
  )(implicit loc: Location): Future[Unit] = {
    withCompletionEdit(query, project, filter) { obtained =>
      assertNoDiff(obtained, expected)
    }
  }

  def basicTest(scalaVersion: String): Future[Unit] = {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": { "scalaVersion": "${scalaVersion}" }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |object A {
           |  // @@
           |}
           |/a/src/main/scala/a/inner/FooSample.scala
           |package a.sample
           |
           |class FooSample
           |object FooSample
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "\"\".substrin@@",
        """|substring(beginIndex: Int): String
           |substring(beginIndex: Int, endIndex: Int): String
           |""".stripMargin,
      )
      _ <- assertCompletion(
        "Stream@@",
        getExpected(
          """|BaseStream - java.util.stream
             |InputStream - java.io
             |IntStream - java.util.stream
             |LogStream - java.rmi.server
             |LongStream - java.util.stream
             |PrintStream - java.io
             |Stream - java.util.stream
             |Stream scala.collection.immutable
             |StreamBuilder - scala.collection.immutable.Stream
             |StreamCanBuildFrom - scala.collection.immutable.Stream
             |StreamFilter - javax.xml.stream
             |StreamResult - javax.xml.transform.stream
             |StreamView - scala.collection.immutable
             |Streamable - scala.reflect.io
             |""".stripMargin,
          Map(
            "2.13" ->
              """|BaseStream - java.util.stream
                 |InputStream - java.io
                 |IntStream - java.util.stream
                 |LogStream - java.rmi.server
                 |LongStream - java.util.stream
                 |PrintStream - java.io
                 |Stream - java.util.stream
                 |Stream scala.collection.immutable
                 |StreamFilter - javax.xml.stream
                 |StreamResult - javax.xml.transform.stream
                 |StreamShape - scala.collection.convert.StreamExtensions
                 |Streamable - scala.reflect.io
                 |""".stripMargin,
            "3" ->
              """|BaseStream - java.util.stream
                 |InputStream - java.io
                 |IntStream - java.util.stream
                 |LogStream - java.rmi.server
                 |LongStream - java.util.stream
                 |PrintStream - java.io
                 |Stream - java.util.stream
                 |Stream - scala.collection.immutable
                 |Stream scala.collection.immutable
                 |StreamFilter - javax.xml.stream
                 |StreamResult - javax.xml.transform.stream
                 |StreamShape - scala.collection.convert.StreamExtensions
                 |StreamSource - javax.xml.transform.stream
                 |Stream[A](elems: A*): CC[A]
                 |""".stripMargin,
          ),
          scalaVersion,
        ),
      )
      _ <- assertCompletion(
        "TrieMap@@",
        getExpected(
          """|HashTrieMap - scala.collection.immutable.HashMap
             |ParTrieMap - scala.collection.parallel.mutable
             |ParTrieMapCombiner - scala.collection.parallel.mutable
             |ParTrieMapSplitter - scala.collection.parallel.mutable
             |TrieMap - scala.collection.concurrent
             |TrieMapSerializationEnd - scala.collection.concurrent
             |""".stripMargin,
          Map(
            "2.13" ->
              """|TrieMap - scala.collection.concurrent
                 |TrieMapSerializationEnd - scala.collection.concurrent
                 |""".stripMargin,
            "3" ->
              """|TrieMap - scala.collection.concurrent
                 |TrieMap[K, V](elems: (K, V)*): CC[K, V]
                 |""".stripMargin,
          ),
          scalaVersion,
        ),
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
           |""".stripMargin,
      )
      _ <- assertCompletion(
        """
          |val a: FooSa@@
          |""".stripMargin,
        """|FooSample - a.sample
           |""".stripMargin,
      )
    } yield ()
  }

  def matchKeywordTest(scalaVersion: String): Future[Unit] = {
    cleanWorkspace()
    for {
      _ <- initialize(
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
        filter = _.contains("exhaustive"),
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
        filter = _.contains("exhaustive"),
      )
    } yield ()
  }
}
