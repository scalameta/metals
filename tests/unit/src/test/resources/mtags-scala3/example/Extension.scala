/*example.Extension$package.*/package example

extension (i/*Extension$package.asString().(i)*/: Int)
  def asString/*Extension$package.asString().*/: String = i.toString

extension (s/*Extension$package.asInt().(s)*//*Extension$package.double().(s)*/: String) {
  def asInt/*Extension$package.asInt().*/: Int = s.toInt
  def double/*Extension$package.double().*/: String = s * 2
}

trait AbstractExtension/*example.AbstractExtension#*/ {
  extension (d/*example.AbstractExtension#abc().(d)*/: Double) {
    def abc/*example.AbstractExtension#abc().*/: String
  }
}
