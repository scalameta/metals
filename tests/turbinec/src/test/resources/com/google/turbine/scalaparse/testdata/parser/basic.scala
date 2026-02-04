package foo.bar

import foo.bar.Baz

case class Box[A <: AnyRef, B >: Null](val x: A, y: Int = 1) extends Base with Trait {
  def foo(x: Int): String = x.toString
  val bar: Int = 42
  type Alias = List[A]
}

object Box {
  def apply(x: Int): Box[Int] = new Box(x)
}
