def foobar(): Unit =>>region>>
  ???
  ???
  ???
  ???<<region<<

object Foo>>region>>:
  def foo =>>region>>
    ???
    ???<<region<<<<region<<

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

def fooWithMatch(): Unit =>>region>>
  def bar(): Unit =>>region>>
    ??? match>>region>>
      case 1 =>>>region>>
        ???
        ???
        ???<<region<<
      case 2 =>>>region>>
        ??? match>>region>>
          case 3 => ???
          case 4 => ???
          case 5 => ???
          case 6 => ???<<region<<
        ???
        ???
        ???<<region<<<<region<<<<region<<
  ???
  ???
  ???
  ???
  ???
  ???
  ???<<region<<

def noFoldHere(): Unit = ???

def noFoldInside(): Unit =>>region>>
  ??? match
    case ??? => ???

  for ??? <- ???
  yield ???<<region<<

class Bar>>region>>:
  def foo() =>>region>>
    ???
    ???
    ???<<region<<

  def bar() =>>region>>
    ???
    ???
    ???<<region<<<<region<<

trait Bar2>>region>>:
  def foo(): Unit

  def bar(): Unit<<region<<
