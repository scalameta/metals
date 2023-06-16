/*example.nested(Package):21*/package example.nested

/*example.nested.LocalDeclarations(Interface):4*/trait LocalDeclarations:
  /*example.nested.LocalDeclarations#foo(Method):4*/def foo(): Unit

/*example.nested.Foo(Interface):7*/trait Foo:
  /*example.nested.Foo#y(Constant):7*/val y = 3

/*example.nested.LocalDeclarations(Module):21*/object LocalDeclarations:
  /*example.nested.LocalDeclarations.create(Method):21*/def create(): LocalDeclarations =
    /*example.nested.LocalDeclarations.create.bar(Method):11*/def bar(): Unit = ()

    /*example.nested.LocalDeclarations.create.x(Constant):14*/val x = /*example.nested.LocalDeclarations.create.x.new (anonymous)(Interface):14*/new:
      /*example.nested.LocalDeclarations.create.x.`new (anonymous)`#x(Constant):14*/val x = 2

    /*example.nested.LocalDeclarations.create.y(Constant):16*/val y = new Foo {}

    /*example.nested.LocalDeclarations.create.yy(Constant):18*/val yy = y.y

    /*example.nested.LocalDeclarations.create.new LocalDeclarations with Foo(Interface):21*/new LocalDeclarations with Foo:
      /*example.nested.LocalDeclarations.create.`new LocalDeclarations with Foo`#foo(Method):21*/override def foo(): Unit = bar()
