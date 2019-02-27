package example

import scala.language.dynamics

class Foo extends Dynamic {
  def banana: String = "42"
  def selectDynamic(a: String): Foo = this
  def applyDynamicNamed(name: String)(arg: (String, Int)): Foo = this
  def updateDynamic(name: String)(value: Int): Foo = this
}

object User {
  val foo = new Foo()
  (foo.foobar = 2).ban
}
