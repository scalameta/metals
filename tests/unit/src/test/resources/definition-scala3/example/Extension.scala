package example

extension/*<no symbol>*/ (i/*Extension.scala*/: Int/*Int.scala*/) def asString/*Extension.scala*/: String/*Predef.scala*/ = i/*Extension.scala*/.toString/*Any.scala*/

extension/*<no symbol>*/ (s/*Extension.scala*/: String/*Predef.scala*/)
  def asInt/*Extension.scala*/: Int/*Int.scala*/ = s/*Extension.scala*/.toInt/*StringOps.scala*/
  def double/*Extension.scala*/: String/*Predef.scala*/ = s/*Extension.scala*/ */*StringOps.scala*/ 2

trait AbstractExtension/*Extension.scala*/:
  extension/*<no symbol>*/ (d/*Extension.scala*/: Double/*Double.scala*/)
    def abc/*Extension.scala*/: String/*Predef.scala*/
