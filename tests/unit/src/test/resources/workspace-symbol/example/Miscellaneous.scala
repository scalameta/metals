package example

class Miscellaneous/*example.Miscellaneous#*/ {
  // backtick identifier
  val `a b`/*example.Miscellaneous#`a b`.*/ = 42

  // block with only wildcard value
  def apply/*example.Miscellaneous#apply().*/(): Unit = {
    val _ = 42
  }
  // infix + inferred apply/implicits/tparams
  (List(1)
    .map(_ + 1)
    ++
      List(3))
}
