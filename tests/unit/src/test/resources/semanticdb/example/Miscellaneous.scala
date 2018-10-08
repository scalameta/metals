package example

class Miscellaneous/*example.Miscellaneous#*/ {
  // backtick identifier
  val `a b`/*example.Miscellaneous#`a b`.*/ = 42

  // infix + inferred apply/implicits/tparams
  (List/*scala.collection.immutable.List.*/(1)
    .map/*scala.collection.immutable.List#map().*/(_ +/*scala.Int#`+`(+4).*/ 1)
    ++/*scala.collection.immutable.List#`++`().*/
      List/*scala.collection.immutable.List.*/(3))
}
