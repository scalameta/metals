package example

trait Ord/*Ord.scala*/[T/*Ord.scala*/]:
   def compare/*Ord.scala*/(x/*Ord.scala*/: T/*Ord.scala*/, y/*Ord.scala*/: T/*Ord.scala*/): Int/*Int.scala*/

given intOrd/*Ord.scala*/: Ord/*Ord.scala*/[Int/*Int.scala*/] with
   def compare/*Ord.scala*/(x/*Ord.scala*/: Int/*Int.scala*/, y/*Ord.scala*/: Int/*Int.scala*/) =
     if x/*Ord.scala*/ </*Int.scala*/ y/*Ord.scala*/ then -/*<no symbol>*/1 else if x/*Ord.scala*/ >/*Int.scala*/ y/*Ord.scala*/ then +/*<no symbol>*/1 else 0

given Ord/*Ord.scala*/[String/*Predef.scala*/] with
   def compare/*Ord.scala*/(x/*Ord.scala*/: String/*Predef.scala*/, y/*Ord.scala*/: String/*Predef.scala*/) =
     x/*Ord.scala*/.compare/*StringOps.scala*/(y/*Ord.scala*/)
