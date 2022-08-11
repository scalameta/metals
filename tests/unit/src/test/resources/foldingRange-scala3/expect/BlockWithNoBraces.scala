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