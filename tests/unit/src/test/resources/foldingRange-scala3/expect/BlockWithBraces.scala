def foobar(): Unit = >>region>>{
  ???
  ???
  ???
  ???
}<<region<<

def test(): Unit = >>region>>{
  try >>region>>{
    ???
    ???
    ???
    ???
    ???
  }<<region<<
  catch >>region>>{
    case ??? => ???
    case ??? => ???
    case ??? => ???
    case ??? => ???
    case ??? => ???
  }<<region<<

  ??? match >>region>>{
    case ??? => ???
    case ??? => ???
  }<<region<<

  ??? match >>region>>{
    case ??? => ???
    case ??? => ???
  }<<region<<

  val zyx =>>region>> ??? match {
    case ??? => ???
    case ??? => ???
    case ??? => ???
    case ??? => ???
  }<<region<<
}<<region<<

def noFoldHere(): Unit = { ??? }

def noFoldInside(): Unit =>>region>>
  ??? match
  { case ??? => ??? }

  for { ??? <- ??? }
  yield ???<<region<<

class Bar >>region>>{
  def foo() = >>region>>{
    ???
    ???
    ???
  }<<region<<

  def bar() = >>region>>{
    ???
    ???
    ???
  }<<region<<

  def fbar() =>>region>>
  {
    ???
    ???
    ???
  }<<region<<
}<<region<<

trait Bar2 >>region>>{
  def foo(): Unit

  def bar(): Unit
}<<region<<