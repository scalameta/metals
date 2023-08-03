package example

extension/*example.Extension$package.*/ (i: Int) def asString: String = i.toString

extension (s: String)
  def asInt: Int = s.toInt
  def double: String = s * 2

trait AbstractExtension/*example.AbstractExtension#*/:
  extension (d: Double)
    def abc: String
