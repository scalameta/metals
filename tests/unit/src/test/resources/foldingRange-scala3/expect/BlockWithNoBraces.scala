def fooWithMatch(): Unit =>>region>>
  def bar(): Unit =>>region>>
    ??? match>>region>>
      case 1 => ???
      case 2 =>>>region>>
        ??? match
          case 3 => ???
          case 4 => ???
          case 5 => ???
          case 6 => ???<<region<<<<region<<
    ???
    ???
    ???<<region<<
  ???
  ???
  ???
  ???
  ???<<region<<

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
    ??? match
      case 1 => {>>region>>
        ???
        ???
        ???
      }<<region<<
      case 2 =>>>region>>
        ??? match>>region>>
          case 3 => ???
          case 4 => ???
          case 5 => ???
          case 6 => ???<<region<<
        ???
        ???
        ???<<region<<<<region<<
  ???
  ???
  ???
  ???
  ???
  ???
  ???<<region<<
