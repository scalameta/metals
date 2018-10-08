package example

class TypeParameters[A] {
  def method[B] = 42
  trait TraitParameter[C]
  type AbstractTypeAlias[D]
  type TypeAlias[E] = List[E]
}
