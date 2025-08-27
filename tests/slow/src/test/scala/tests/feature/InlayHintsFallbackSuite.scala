package tests.feature

import tests.BaseInlayHintsLspSuite

class InlayHintsFallbackSuite
    extends BaseInlayHintsLspSuite(
      "inlayHints-fallback",
      "3.4.0",
    ) {

  /**
   * This should not include positions since, we are testing fallback method of
   * providing inlay hints. 3.4.0 doesn't have it built in the presentation compiler.
   */
  check(
    "all-synthetics",
    """|import scala.concurrent.Future
       |case class Location(city: String)
       |object Main{
       |  def hello()(implicit name: String, from: Location)/*: Unit*/ = {
       |    println(s"Hello $$name from $${from.city}")
       |  }
       |  implicit val andy : String = "Andy"
       |
       |  def greeting()/*: Unit*/ = {
       |    implicit val boston/*: Location*/ = Location("Boston")
       |    hello()/*(andy, boston)*/
       |    hello()/*(andy, boston)*/;    hello()/*(andy, boston)*/
       |  }
       |  
       |  val ordered/*: String*/ = /*augmentString(*/"acb"/*)*/.sorted/*(Char)*/
       |  /*augmentString(*/"foo"/*)*/.map(c/*: Char*/ => c.toInt)
       |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
       |  Future{
       |    println("")
       |  }/*(ec)*/
       |}
       |""".stripMargin,
    config = Some(
      """|"inferredTypes": {"enable":true},
         |"implicitConversions": {"enable":true},
         |"implicitArguments": {"enable":true},
         |"typeParameters": {"enable":false},
         |"hintsInPatternMatch": {"enable":true}
         |""".stripMargin
    ),
  )

}
