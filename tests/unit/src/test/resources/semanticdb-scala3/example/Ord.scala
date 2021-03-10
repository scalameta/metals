package example

trait Ord/*example.Ord#*/[T/*example.Ord#[T]*/]:
   def compare/*example.Ord#compare().*/(x/*example.Ord#compare().(x)*/: T/*example.Ord#[T]*/, y/*example.Ord#compare().(y)*/: T/*example.Ord#[T]*/): Int/*scala.Int#*/

/*example.Ord$package.*/given intOrd/*example.Ord$package.intOrd.*/: Ord/*example.Ord#*/[Int/*scala.Int#*/] with
   def compare/*example.Ord$package.intOrd.compare().*/(x/*example.Ord$package.intOrd.compare().(x)*/: Int/*scala.Int#*/, y/*example.Ord$package.intOrd.compare().(y)*/: Int/*scala.Int#*/) =
     if x/*example.Ord$package.intOrd.compare().(x)*/ </*scala.Int#`<`(+3).*/ y/*example.Ord$package.intOrd.compare().(y)*/ then -1 else if x/*example.Ord$package.intOrd.compare().(x)*/ >/*scala.Int#`>`(+3).*/ y/*example.Ord$package.intOrd.compare().(y)*/ then +1/*scala.Int#`unary_+`().*/ else 0

given Ord/*example.Ord#*/[String/*scala.Predef.String#*/] with/*example.Ord$package.given_Ord_String.*/
   def compare/*example.Ord$package.given_Ord_String.compare().*/(x/*example.Ord$package.given_Ord_String.compare().(x)*/: String/*scala.Predef.String#*/, y/*example.Ord$package.given_Ord_String.compare().(y)*/: String/*scala.Predef.String#*/) =
     /*scala.Predef.augmentString().*/x/*example.Ord$package.given_Ord_String.compare().(x)*/.compare/*scala.collection.StringOps#compare().*/(y/*example.Ord$package.given_Ord_String.compare().(y)*/)
