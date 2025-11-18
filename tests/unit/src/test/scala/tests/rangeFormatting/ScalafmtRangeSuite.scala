package tests.rangeFormatting

import scala.meta.internal.metals.Configs.RangeFormattingProviders
import scala.meta.internal.metals.UserConfiguration

import tests.BaseLspSuite

class ScalafmtRangeSuite extends BaseLspSuite("ScalafmtRangeFormatting") {

  override def userConfig: UserConfiguration = super.userConfig.copy(
    rangeFormattingProviders = RangeFormattingProviders(List("scalafmt"))
  )

  testLSP("basic") {
    val a = "a/src/main/scala/a/Main.scala"
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{}}
           |/$a
           |package a
           |object Main
           |""".stripMargin
      )
      _ <- server.didOpen(a)
      _ <- server.rangeFormatting(
        a,
        """
          |package a
          |object Main {
          |  def main(args: Array[String]) {
          |       println(   "Hello world1!"  )     // Strange formatting
          |        <<println(   "Hello world2!"   )      // Strange formatting>>
          |         println(   "Hello world3!"  )     // Strange formatting
          |  }
          |}
          |""".stripMargin,
        """|package a
           |object Main {
           |  def main(args: Array[String]) {
           |       println(   "Hello world1!"  )     // Strange formatting
           |    println("Hello world2!") // Strange formatting
           |         println(   "Hello world3!"  )     // Strange formatting
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("no-indent") {
    val a = "a/src/main/scala/a/Main.scala"
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{}}
           |/$a
           |package a
           |object Main
           |""".stripMargin
      )
      _ <- server.didOpen(a)
      _ <- server.rangeFormatting(
        a,
        """
          |package a
          |object Main {
          |  def main(args: Array[String]) {
          |       println(   "Hello world1!"  )     // Strange formatting
          |<<println   ("Hello world2!") // Strange formatting>>
          |         println(   "Hello world3!"  )     // Strange formatting
          |  }
          |}
          |""".stripMargin,
        """|package a
           |object Main {
           |  def main(args: Array[String]) {
           |       println(   "Hello world1!"  )     // Strange formatting
           |    println("Hello world2!") // Strange formatting
           |         println(   "Hello world3!"  )     // Strange formatting
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}
