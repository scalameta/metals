package example

case class User(
    name: String,
    age: Int = 42,
    address: String = "",
    followers: Int = 0
)

object User {}
