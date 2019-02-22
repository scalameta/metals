package example

import shapeless._
case class Person(name: String, age: Int)
object User {
  val gen = Generic[Person]
  gen.to
}
