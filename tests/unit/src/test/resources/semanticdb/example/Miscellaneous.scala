package example

class Miscellaneous/*example.Miscellaneous#*/ {
  // backtick identifier
  val `a b`/*example.Miscellaneous#`a b`.*/ = 42

  // block with only wildcard value
  def apply/*example.Miscellaneous#apply().*/(): Unit/*scala.Unit#*/ = {
    val _ = 42
  }
  // infix + inferred apply/implicits/tparams
  (List/*scala.package.List.*/(1)
    .map/*scala.collection.immutable.List#map().*/(_ +/*scala.Int#`+`(+4).*/ 1)
    ++/*scala.collection.IterableOps#`++`().*/
      List/*scala.package.List.*/(3))
}
