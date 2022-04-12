package example

extension/*<no symbol>*/ (i/*Extension.scala fallback to example.Extension$package.*/: Int/*Int.scala*/)
  def asString/*Extension.scala fallback to example.Extension$package.*/: String/*Predef.scala*/ = i/*Extension.scala fallback to example.Extension$package.*/.toString

extension/*<no symbol>*/ (s/*Extension.scala fallback to example.Extension$package.*/: String/*Predef.scala*/) {
  def asInt/*Extension.scala fallback to example.Extension$package.*/: Int/*Int.scala*/ = s/*Extension.scala fallback to example.Extension$package.*/.toInt/*StringOps.scala*/
  def double/*Extension.scala fallback to example.Extension$package.*/: String/*Predef.scala*/ = s/*Extension.scala fallback to example.Extension$package.*/ */*StringOps.scala*/ 2
}

trait AbstractExtension/*Extension.scala*/ {
  extension/*<no symbol>*/ (d/*Extension.scala*/: Double/*Double.scala*/) {
    def abc/*Extension.scala*/: String/*Predef.scala*/
  }
}
