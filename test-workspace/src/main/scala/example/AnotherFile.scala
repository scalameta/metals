package example2

case class Bananas(aaaaaa: Int, bbb: String)


object AnotherFile {
    val b = Bananas.apply(aaaaaa = 1, bbb = "")
  b.copy(aaaaaa = 2, bbb = "")
  def jumpHere = 2
  def blah = 2
}
