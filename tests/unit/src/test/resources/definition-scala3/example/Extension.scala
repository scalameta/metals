package example

extension/*<no symbol>*/ (i/*<no file>*/: Int/*Int.scala*/)
  def asString/*Extension.scala*/: String/*Predef.scala*/ = i/*<no file>*/.toString

extension/*<no symbol>*/ (s/*<no file>*/: String/*Predef.scala*/) {
  def asInt/*Extension.scala*/: Int/*Int.scala*/ = s/*<no file>*/.toInt/*StringOps.scala*/
  def double/*Extension.scala*/: String/*Predef.scala*/ = s/*<no file>*/ */*StringOps.scala*/ 2
}

trait AbstractExtension/*Extension.scala*/ {
  extension/*<no symbol>*/ (d/*Extension.scala fallback to example.AbstractExtension#*/: Double/*Double.scala*/) {
    def abc/*Extension.scala*/: String/*Predef.scala*/
  }
}
