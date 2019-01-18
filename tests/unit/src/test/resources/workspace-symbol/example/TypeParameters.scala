package example

class TypeParameters/*example.TypeParameters#*/[A] {
  def method[B] = 42
  trait TraitParameter/*example.TypeParameters#TraitParameter#*/[C]
  type AbstractTypeAlias[D]
  type TypeAlias[E] = List[E]
}
