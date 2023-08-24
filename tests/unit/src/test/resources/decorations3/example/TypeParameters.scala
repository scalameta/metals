package example

class TypeParameters[A] {
  def method[B]/*: Int*/ = 42
  trait TraitParameter[C]
  type AbstractTypeAlias[D]
  type TypeAlias[E] = List[E]
}