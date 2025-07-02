package tests.play

import tests.BaseSuite
import scala.meta.internal.metals.TwirlAdjustments
import java.io.File

class TwirlSuite extends BaseSuite {

  val scalaVersion = "3.7.1"

  val inputFile =
    new File(
      "/home/ajafri/scala/play-test/target/scala-3.7.1/twirl/main/html/example.template.scala"
    )

  test("twirl-mapping") {
    assert(5 == 5)
  }

}
