package tests

class AnalyzeStacktraceLspSuite
    extends BaseAnalyzeStacktraceSuite("analyzestacktrace") {

  check(
    "simple",
    code,
    """|Exception in thread "main" java.lang.Exception: error
       |	at a.b.ClassConstrError.<init>(Main.scala:24)
       |	at a.b.ObjectError$.raise(Main.scala:18)
       |	at a.b.ClassError.raise(Main.scala:12)
       |	at a.b.Main$.main(Main.scala:5)
       |	at a.b.Main.main(Main.scala)
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
  )

  check(
    "cat-effect-stactraces",
    catsEffectCode,
    """|java.lang.Exception
       |        at a.Main$.$anonfun$run$1(Stacktraces.scala:10)
       |        at apply @ a.Main$.<clinit>(Stacktraces.scala:7)
       |        at map @ a.Main$.run(Stacktraces.scala:9)
       |        at run$ @ a.Main$.run(Stacktraces.scala:6)
       |""".stripMargin,
    dependency = "\"org.typelevel::cats-effect:3.3.11\"",
  )

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

  private lazy val catsEffectCode =
    """|package a
       |
       |import cats.effect.IOApp
       |import cats.effect.IO
       |
       |<<4>>object Main extends IOApp.Simple {
       |<<2>>  val io = IO(5)
       |  override def run: IO[Unit] = io
       |<<3>>      .map { _ => 
       |<<1>>        throw new Exception
       |        ()
       |      }
       |}
       |""".stripMargin

}
