/*example.nested(Package):28*/package example.nested

/*example.nested.LocalDeclarations(Interface):5*/trait LocalDeclarations {
  /*example.nested.LocalDeclarations#foo(Method):4*/def foo(): Unit
}

/*example.nested.Foo(Interface):9*/trait Foo {
  /*example.nested.Foo#y(Constant):8*/val y = 3
}

/*example.nested.LocalDeclarations(Module):28*/object LocalDeclarations {
  /*example.nested.LocalDeclarations.create(Method):27*/def create(): LocalDeclarations = {
    /*example.nested.LocalDeclarations.create.bar(Method):13*/def bar(): Unit = ()

    /*example.nested.LocalDeclarations.create.x(Constant):17*/val x = /*example.nested.LocalDeclarations.create.x.new (anonymous)(Interface):17*/new {
      /*example.nested.LocalDeclarations.create.x.`new (anonymous)`#x(Constant):16*/val x = 2
    }

    /*example.nested.LocalDeclarations.create.y(Constant):19*/val y = new Foo {}

    x.x + y.y

    /*example.nested.LocalDeclarations.create.new LocalDeclarations with Foo(Interface):25*/new LocalDeclarations with Foo {
      /*example.nested.LocalDeclarations.create.`new LocalDeclarations with Foo`#foo(Method):24*/override def foo(): Unit = bar()
    }

  }
}
