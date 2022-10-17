/*example.Extension$package.*/package example

extension (i/*example.Extension$package.asString().(i)*/: Int)
  def asString/*example.Extension$package.asString().*/: String = i.toString

extension (s/*example.Extension$package.asInt().(s)*//*example.Extension$package.double().(s)*/: String) {
  def asInt/*example.Extension$package.asInt().*/: Int = s.toInt
  def double/*example.Extension$package.double().*/: String = s * 2
}

trait AbstractExtension/*example.AbstractExtension#*/ {
  extension (d/*example.AbstractExtension#abc().(d)*/: Double) {
    def abc/*example.AbstractExtension#abc().*/: String
  }
}
