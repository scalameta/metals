class A {
  def foo =
    for {
      x <- ???
    } yield {
      ???
    }

  def noSpacing =
    for{
      x <- ???
    }yield{
      ???
    }

  def why =
    for

      {
      x <- ???
      y <- ???
      z <- ???
    }

    // because why not...
      yield


  // yes, it is the body
  {
    ???
  }
}
