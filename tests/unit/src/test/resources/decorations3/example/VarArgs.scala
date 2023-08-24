package example

class VarArgs {
  def add(a: Int*)/*: Seq[Int]*/ = a
  def add2(a: Seq[Int]*)/*: Seq[Seq[Int]]*/ = a
}