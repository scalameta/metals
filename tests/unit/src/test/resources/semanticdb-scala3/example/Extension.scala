package example

/*example.Extension$package.*/extension (i/*example.Extension$package.asString().(i)*/: Int/*scala.Int#*/)
  def asString/*example.Extension$package.asString().*/: String/*scala.Predef.String#*/ = i/*example.Extension$package.asString().(i)*/.toString/*scala.Any#toString().*/

extension (s/*example.Extension$package.asInt().(s)*//*example.Extension$package.double().(s)*/: String/*scala.Predef.String#*/) {
  def asInt/*example.Extension$package.asInt().*/: Int/*scala.Int#*/ = /*scala.Predef.augmentString().*/s/*example.Extension$package.asInt().(s)*/.toInt/*scala.collection.StringOps#toInt().*/
  def double/*example.Extension$package.double().*/: String/*scala.Predef.String#*/ = /*scala.Predef.augmentString().*/s/*example.Extension$package.double().(s)*/ */*scala.collection.StringOps#`*`().*/ 2
}

trait AbstractExtension/*example.AbstractExtension#*/ {
  extension (d/*example.AbstractExtension#abc().(d)*/: Double/*scala.Double#*/) {
    def abc/*example.AbstractExtension#abc().*/: String/*scala.Predef.String#*/
  }
}
