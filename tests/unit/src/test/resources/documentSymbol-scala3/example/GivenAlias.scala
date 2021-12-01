/*example(Package):49*/package example

/*example.intValue(Constant):3*/given intValue: Int = 4
/*example. (Constant):4*/given String = "str"
/*example. (Constant):5*/given (using i: Int): Double = 4.0
/*example. (Constant):6*/given [T]: List[T] = Nil
/*example.given_Char(Constant):7*/given given_Char: Char = '?'
/*example.given_Float(Constant):8*/given `given_Float`: Float = 3.0
/*example.* *(Constant):9*/given `* *`: Long = 5

/*example.method(Method):11*/def method(using Int) = ""

/*example.X(Module):18*/object X {
  /*example.X. (Constant):14*/given Double = 4.0
  /*example.X.double(Constant):15*/val double = given_Double

  /*example.X.of(Constant):17*/given of[A]: Option[A] = ???
}

/*example.Xg(Interface):21*/trait Xg:
  /*example.Xg#doX(Method):21*/def doX: Int

/*example.Yg(Interface):24*/trait Yg:
  /*example.Yg#doY(Method):24*/def doY: String

/*example.Zg(Interface):27*/trait Zg[T]:
  /*example.Zg#doZ(Method):27*/def doZ: List[T]

/*example. (Class):30*/given Xg with
  /*example.` `#doX(Method):30*/def doX = 7

/*example. (Class):33*/given (using Xg): Yg with
  /*example.` `#doY(Method):33*/def doY = "7"

/*example. (Class):36*/given [T]: Zg[T] with
  /*example.` `#doZ(Method):36*/def doZ: List[T] = Nil


/*example.a(Constant):39*/val a = intValue
/*example.b(Constant):40*/val b = given_String
/*example.c(Constant):41*/val c = X.given_Double
/*example.d(Constant):42*/val d = given_List_T[Int]
/*example.e(Constant):43*/val e = given_Char
/*example.f(Constant):44*/val f = given_Float
/*example.g(Constant):45*/val g = `* *`
/*example.i(Constant):46*/val i = X.of[Int]
/*example.x(Constant):47*/val x = given_Xg
/*example.y(Constant):48*/val y = given_Yg
/*example.z(Constant):49*/val z = given_Zg_T[String]

