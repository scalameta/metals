package tests

import scala.collection.mutable

import org.eclipse.{lsp4j => l}

class AnalyzeStacktraceLspSuite extends BaseLspSuite("analyzestacktrace") {

  check(
    "simple",
    code,
    """|Exception in thread "main" java.lang.Exception: error
       |	at a.b.ClassConstrError.<init>(Main.scala:24)
       |	at a.b.ObjectError$.raise(Main.scala:18)
       |	at a.b.ClassError.raise(Main.scala:12)
       |	at a.b.Main$.main(Main.scala:5)
       |	at a.b.Main.main(Main.scala)
       |""".stripMargin
  )

  check(
    "bloop-cli",
    code,
    """|[E] Exception in thread "main" java.lang.Exception: error
       |[E] 	at a.b.ClassConstrError.<init>(Main.scala:24)
       |[E] 	at a.b.ObjectError$.raise(Main.scala:18)
       |[E] 	at a.b.ClassError.raise(Main.scala:12)
       |[E] 	at a.b.Main$.main(Main.scala:5)
       |[E] 	at a.b.Main.main(Main.scala)
       |""".stripMargin
  )

  /**
   * This stack trace in the check here is the part of the trace that matches
   * the output of the rest above. However, notable I remove the following:
   *
   * [error]         at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
   * [error]         at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
   * [error]         at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
   * [error]         at java.lang.reflect.Method.invoke(Method.java:498)
   *
   * I remove it mainly because it works exactly the same as the rest of the lens
   * functionality, but instead I want to just ensure that the `[error]` here is being
   * striped out correctly.
   */
  check(
    "sbt",
    code,
    """|[error] java.lang.Exception: error
       |[error]         at a.b.ClassConstrError.<init>(Main.scala:24)
       |[error]         at a.b.ObjectError$.raise(Main.scala:18)
       |[error]         at a.b.ClassError.raise(Main.scala:12)
       |[error]         at a.b.Main$.main(Main.scala:5)
       |[error]         at a.b.Main.main(Main.scala)
       |""".stripMargin
  )

  /**
   * If you have a stack trace during test with sbt it won't actually be an
   * `[error]` but rather `[info]` like in the case below... which I just ran
   * into in real life.
   *
   * [info]   com.spotify.docker.client.exceptions.DockerException: java.util.concurrent.ExecutionException: javax.ws.rs.ProcessingException: java.lang.Abstract
   * [info]   at com.spotify.docker.client.DefaultDockerClient.propagate(DefaultDockerClient.java:2812)
   * [info]   at com.spotify.docker.client.DefaultDockerClient.request(DefaultDockerClient.java:2666)
   * [info]   at com.spotify.docker.client.DefaultDockerClient.listImages(DefaultDockerClient.java:690)
   */
  check(
    "sbt-info",
    code,
    """|[info] java.lang.Exception: error
       |[info]         at a.b.ClassConstrError.<init>(Main.scala:24)
       |[info]         at a.b.ObjectError$.raise(Main.scala:18)
       |[info]         at a.b.ClassError.raise(Main.scala:12)
       |[info]         at a.b.Main$.main(Main.scala:5)
       |[info]         at a.b.Main.main(Main.scala)
       |""".stripMargin
  )

  def check(
      name: String,
      code: String,
      stacktrace: String
  ): Unit = {
    test(name) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{"a":{}}
             |/a/src/main/scala/a/Main.scala
             |${prepare(code)}
             |""".stripMargin
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        lenses = server.analyzeStacktrace(stacktrace)
        output =
          lenses
            .map(cl =>
              cl.getRange.getStart.getLine -> cl
                .getCommand()
                .getArguments()
                .get(0)
                .asInstanceOf[l.Location]
                .getRange
                .getStart
                .getLine
            )
            .toMap
        _ = assertEquals(output, getExpected(code))
      } yield ()
    }
  }

  private lazy val code: String =
    """|package a.b
       |
       |object Main {
       |  def main(args: Array[String]): Unit = {
       |<<4>>    new ClassError().raise
       |  }
       |}
       |
       |
       |class ClassError {
       |  def raise: ClassConstrError = {
       |<<3>>    ObjectError.raise
       |  }
       |}
       |
       |object ObjectError {
       |  def raise: ClassConstrError = {
       |<<2>>    new ClassConstrError()
       |  }
       |}
       |
       |class ClassConstrError {
       |  val a = 3
       |<<1>>  throw new Exception("error")
       |  val b = 4
       |}
       |
       |""".stripMargin

  private def getExpected(code: String): Map[Int, Int] = {
    val result: mutable.Buffer[(Int, Int)] = mutable.Buffer()
    for ((line, idx) <- code.split('\n').zipWithIndex) {
      if (line.contains("<<") && line.contains(">>")) {
        val marker = Integer.valueOf(
          line.substring(line.indexOf("<<") + 2, line.indexOf(">>"))
        )
        result += ((marker, idx))
      }
    }
    result.toList.toMap
  }

  private def prepare(code: String): String = {
    code.replaceAll("<<.*>>", "")
  }
}
