package example

class UserTest {
  val user = a.User.apply(name = "", 1)
  locally {
    val x = List(1, 2, 3).map(_ + 2)
  }
  user.copy(age = 2)
}
