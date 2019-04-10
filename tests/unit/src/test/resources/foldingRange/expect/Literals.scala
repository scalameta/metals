class A>>region>>{
  val multilineString =
    """>>region>>
      |
      |
      |
      |
      |
      |
    """<<region<<.stripMargin

  val b = ???
}<<region<<