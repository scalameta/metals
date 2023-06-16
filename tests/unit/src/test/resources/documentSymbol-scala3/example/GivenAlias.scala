/*example(Package):47*/package example

/*example.intValue(Constant):3*/given intValue: Int = 4
/*example. (Constant):4*/given String = "str"
/*example. (Constant):5*/given (using i: Int): Double = 4.0
/*example. (Constant):6*/given [T]: List[T] = Nil
/*example.given_Char(Constant):7*/given given_Char: Char = '?'
/*example.given_Float(Constant):8*/given `given_Float`: Float = 3.0
/*example.* *(Constant):9*/given `* *` : Long = 5

/*example.method(Method):11*/def method(using Int) = ""

/*example.X(Module):17*/object X:
  /*example.X. (Constant):14*/given Double = 4.0
  /*example.X.double(Constant):15*/val double = given_Double

  /*example.X.of(Constant):17*/given of[A]: Option[A] = ???

/*example.Xg(Interface):20*/trait Xg:
  /*example.Xg#doX(Method):20*/def doX: Int

/*example.Yg(Interface):23*/trait Yg:
  /*example.Yg#doY(Method):23*/def doY: String

/*example.Zg(Interface):26*/trait Zg[T]:
  /*example.Zg#doZ(Method):26*/def doZ: List[T]

/*example. (Class):29*/given Xg with
  /*example.` `#doX(Method):29*/def doX = 7

/*example. (Class):32*/given (using Xg): Yg with
  /*example.` `#doY(Method):32*/def doY = "7"

/*example. (Class):35*/given [T]: Zg[T] with
  /*example.` `#doZ(Method):35*/def doZ: List[T] = Nil

/*example.a(Constant):37*/val a = intValue
/*example.b(Constant):38*/val b = given_String
/*example.c(Constant):39*/val c = X.given_Double
/*example.d(Constant):40*/val d = given_List_T[Int]
/*example.e(Constant):41*/val e = given_Char
/*example.f(Constant):42*/val f = given_Float
/*example.g(Constant):43*/val g = `* *`
/*example.i(Constant):44*/val i = X.of[Int]
/*example.x(Constant):45*/val x = given_Xg
/*example.y(Constant):46*/val y = given_Yg
/*example.z(Constant):47*/val z = given_Zg_T[String]
