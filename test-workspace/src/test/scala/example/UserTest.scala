package example
import org.scalatest.FunSuite

class UserTest extends FunSuite {

  test("hello world") {
    val user = a.User.apply(name = "", 1)
    user.copy(age = 2)
    List
      .apply(1, 2)
      .map(x => x.+(user.age))
    scala.runtime.CharRef.create('a')
    val str = user.name + a.a.x
    val left: Either[String, Int] = Left("")
  }

}
