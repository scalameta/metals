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
}<<region<<