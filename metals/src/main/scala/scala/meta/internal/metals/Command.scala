package scala.meta.internal.metals

case class Command(
    id: String,
    title: String,
    description: String
) {
  def unapply(string: String): Boolean = string == id
}
