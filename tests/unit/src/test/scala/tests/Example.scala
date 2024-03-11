package tests

package a
object O {
  trait Foo {
    type T
  }

  implicit class A(val foo: Foo { type T = Int }) {
    def get: Int = 1
  }
}
