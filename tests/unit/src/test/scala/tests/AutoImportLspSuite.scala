package tests

import scala.concurrent.Future

import scala.meta.internal.metals.{BuildInfo => V}

class AutoImportLspSuite extends BaseCompletionLspSuite("auto-import") {

  check(
    "basic",
    """|package a
       |object A {
       |  // @@
       |}
       |""".stripMargin,
    "Future@@.successful(2)" ->
      """|package a
         |
         |import scala.concurrent.Future
         |object A {
         |  Future.successful(2)
         |}
         |""".stripMargin,
    "val uuid = UUID@@.randomUUID()" ->
      """|package a
         |
         |import java.util.UUID
         |object A {
         |  val uuid = UUID.randomUUID()
         |}
         |""".stripMargin,
    "val l : ConcurrentMap@@[String, Int] = ???" ->
      """|package a
         |
         |import java.util.concurrent.ConcurrentMap
         |object A {
         |  val l : ConcurrentMap[String, Int] = ???
         |}
         |""".stripMargin,
    "val l = s\"${ArraySeq@@(2)}\"" ->
      """|package a
         |
         |import scala.collection.mutable.ArraySeq
         |object A {
         |  val l = s"${ArraySeq(2)}"
         |}
         |
         |""".stripMargin,
    "import ExecutionContext@@.global" ->
      """|package a
         |object A {
         |  import scala.concurrent.ExecutionContext.global
         |}
         |""".stripMargin,
  )

  def check(
      name: String,
      layout: String,
      completions: (String, String)*
  ): Unit =
    test(name) {
      val filename = "a/src/main/scala/a/A.scala"
      for {
        _ <- initialize(
          s"""/metals.json
             |{
             |  "a": { "scalaVersion": "${V.latestScala3Next}" }
             |}
             |/$filename
             |$layout
             |""".stripMargin
        )
        _ <- server.didOpen(filename)
        _ = assertNoDiagnostics()
        _ <- completions.foldLeft(Future.unit) {
          case (prev, (input, expected)) =>
            prev.flatMap(_ =>
              assertCompletionEdit(
                input,
                expected,
                filenameOpt = Some(filename),
              )
            )
        }
      } yield ()
    }
}
