package example

class VarArgs {
  def add(a: Int*)/*: Seq<<scala/collection/immutable/Seq#>>[Int<<scala/Int#>>]*/ = a
  def add2(a: Seq[Int]*)/*: Seq<<scala/collection/immutable/Seq#>>[Seq<<scala/collection/immutable/Seq#>>[Int<<scala/Int#>>]]*/ = a
}