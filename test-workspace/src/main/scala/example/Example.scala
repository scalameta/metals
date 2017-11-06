package example

object Example {
  def jess = 2
  AnotherFile.jumpHere
  println(jess)
  println(jess)
  println(jess)
}

class Foo extends SomeTrait with SomeOtherTrait {
  val y = someValue.toString + someOtherValue
}
