package example

object User {
  val member = ""
  val myName = "name"
  def foo = s"""
  ${User.member}
  """
}
