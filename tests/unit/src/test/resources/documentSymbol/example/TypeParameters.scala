/*example(Package):8*/package example

/*example.TypeParameters(Class):8*/class TypeParameters[A] {
  /*example.TypeParameters#method(Method):4*/def method[B] = 42
  /*example.TypeParameters#TraitParameter(Interface):5*/trait TraitParameter[C]
  /*example.TypeParameters#AbstractTypeAlias(Field):6*/type AbstractTypeAlias[D]
  /*example.TypeParameters#TypeAlias(Field):7*/type TypeAlias[E] = List[E]
}
