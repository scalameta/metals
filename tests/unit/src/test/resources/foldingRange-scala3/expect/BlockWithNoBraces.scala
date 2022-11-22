def foobar(): Unit =>>region>>
  ???
  ???
  ???
  ???<<region<<

object Foo>>region>>:
  def foo =
    ???
    ???<<region<<

def endmarker(): Unit =>>region>>
  ???
  ???
  ???
  ???<<region<<
end endmarker

object foo>>region>>:
  println("")
  println("")
  println("")
  println("")
  println("")<<region<<

def fooNested(): Unit =>>region>>
  def bar(): Unit =>>region>>
    ???
    ???
    ???
    ???<<region<<
  ???<<region<<

def fooWithMatch(x: Unit): Unit =>>region>>
  def bar(): Unit =>>region>>
    ??? match>>region>>
      case _: Unit => ???
      case z =>
        z match
          case _: Unit => ???
          case _ => ???<<region<<
    ???<<region<<
  ???<<region<<
