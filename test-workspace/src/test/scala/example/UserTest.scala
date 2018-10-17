package example

import org.scalatest.FunSuite

class UserTest extends FunSuite {
  test("basic") {
  	val susan = User.susan
    val basic: Int = 42
    assert(basic == 42)
  }
}
