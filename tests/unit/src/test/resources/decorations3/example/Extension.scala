package example

extension (i: Int) def asString: String = i.toString

extension (s: String)
  def asInt: Int = /*augmentString(*/s/*)*/.toInt
  def double: String = /*augmentString(*/s/*)*/ * 2

trait AbstractExtension:
  extension (d: Double)
    def abc: String