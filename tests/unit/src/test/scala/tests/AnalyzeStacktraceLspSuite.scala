package tests

import org.eclipse.{lsp4j => l}
import scala.collection.mutable

class AnalyzeStacktraceLspSuite extends BaseLspSuite("analyzestacktrace") {

  test("simple") {
    val code =
      """|package a.b
         |
         |object Main {
         |<<5>>  def main(args: Array[String]): Unit = {
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

    // code above is executed in REPL and resulted stacktrace is printed here:
    val stacktrace = """|Exception in thread "main" java.lang.Exception: error
                        |	at a.b.ClassConstrError.<init>(Main.scala:24)
                        |	at a.b.ObjectError$.raise(Main.scala:18)
                        |	at a.b.ClassError.raise(Main.scala:12)
                        |	at a.b.Main$.main(Main.scala:5)
                        |	at a.b.Main.main(Main.scala)
                        |""".stripMargin

    cleanWorkspace()
    for {
      _ <- server.initialize(
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
