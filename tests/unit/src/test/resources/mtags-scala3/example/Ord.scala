/*example.Ord$package.*/package example

trait Ord/*example.Ord#*/[T/*example.Ord#[T]*/]:
   def compare/*example.Ord#compare().*/(x/*example.Ord#compare().(x)*/: T, y/*example.Ord#compare().(y)*/: T): Int

given intOrd/*example.Ord$package.intOrd.*/: Ord[Int] with
   def compare/*example.Ord$package.intOrd.compare().*/(x/*example.Ord$package.intOrd.compare().(x)*/: Int, y/*example.Ord$package.intOrd.compare().(y)*/: Int) =
     if x < y then -1 else if x > y then +1 else 0

given Ord[String]/*example.Ord$package.given_Ord_String.*/ with
   def compare/*example.Ord$package.given_Ord_String.compare().*/(x/*example.Ord$package.given_Ord_String.compare().(x)*/: String, y/*example.Ord$package.given_Ord_String.compare().(y)*/: String) =
     x.compare(y)
