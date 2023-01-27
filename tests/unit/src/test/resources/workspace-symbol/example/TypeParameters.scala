package example

class TypeParameters/*example.TypeParameters#*/[A] {
  def method/*example.TypeParameters#method().*/[B] = 42
  trait TraitParameter/*example.TypeParameters#TraitParameter#*/[C]
  type AbstractTypeAlias/*example.TypeParameters#AbstractTypeAlias#*/[D]
  type TypeAlias/*example.TypeParameters#TypeAlias#*/[E] = List[E]
}
