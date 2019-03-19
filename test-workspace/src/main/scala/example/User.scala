package example

trait Samda {
  def foobar(a: Int, b: Double): String
}

object Main {
  def foobar(fn: Samda): Unit = ()
  foobar {
    case (a, b) => ""
  }
}
