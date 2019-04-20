/*example.nested(Package):24*/package example.nested

/*example.nested.LocalDeclarations(Interface):5*/trait LocalDeclarations {
  /*example.nested.LocalDeclarations#foo(Method):4*/def foo(): Unit
}

/*example.nested.Foo(Interface):7*/trait Foo {}

/*example.nested.LocalDeclarations(Module):24*/object LocalDeclarations {
  /*example.nested.LocalDeclarations.create(Method):23*/def create(): LocalDeclarations = {
    /*example.nested.LocalDeclarations.create.bar(Method):11*/def bar(): Unit = ()

    /*example.nested.LocalDeclarations.create.x(Constant):15*/val x = /*example.nested.LocalDeclarations.create.x.new (anonymous)(Interface):15*/new {
      /*example.nested.LocalDeclarations.create.x.`new (anonymous)`#x(Constant):14*/val x = 2
    }

    /*example.nested.LocalDeclarations.create.y(Constant):17*/val y = new Foo {}

    /*example.nested.LocalDeclarations.create.new LocalDeclarations with Foo(Interface):21*/new LocalDeclarations with Foo {
      /*example.nested.LocalDeclarations.create.`new LocalDeclarations with Foo`#foo(Method):20*/override def foo(): Unit = bar()
    }

  }
}
