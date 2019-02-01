package example

class VarArgs {
  def add(a: Int*) = a
  def add2(a: Seq[Int]*) = a
}
