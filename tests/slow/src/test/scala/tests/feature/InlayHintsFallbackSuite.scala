package tests.feature

import tests.BaseInlayHintsLspSuite

class InlayHintsFallbackSuite
    extends BaseInlayHintsLspSuite(
      "inlayHints-fallback",
      "3.4.0",
    ) {

  check(
    "all-synthetics",
    """|import scala.concurrent.Future
       |case class Location(city: String)
       |object Main{
       |  def hello()(implicit name: String, from: Location)/*: Unit<<scala/Unit#>>*/ = {
       |    println(s"Hello $$name from $${from.city}")
       |  }
       |  implicit val andy : String = "Andy"
       |
       |  def greeting()/*: Unit<<scala/Unit#>>*/ = {
       |    implicit val boston/*: Location<<(1:11)>>*/ = Location("Boston")
       |    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/
       |    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/;    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/
       |  }
       |  
       |  val ordered/*: String<<scala/Predef.String#>>*/ = /*augmentString<<scala/Predef.augmentString().>>(*/"acb"/*)*/.sorted/*(Char<<scala/math/Ordering.Char.>>)*/
       |  /*augmentString<<scala/Predef.augmentString().>>(*/"foo"/*)*/.map(c/*: Char<<scala/Char#>>*/ => c.toInt)
       |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
       |  Future{
       |    println("")
       |  }/*(ec<<(16:15)>>)*/
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
