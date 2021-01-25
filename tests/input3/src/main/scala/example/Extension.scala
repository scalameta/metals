package example

extension (i: Int)
  def asString: String = i.toString

extension (s: String) {
  def asInt: Int = s.toInt
  def double: String = s * 2
}
