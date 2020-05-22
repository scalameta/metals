package scala.meta.internal.metals

/**
 * Small utilities that are helpful for println debugging.
 */
object Debug {
  def printStack(): Unit = {
    val e = new Exception()
    e.setStackTrace(e.getStackTrace.slice(2, 30))
    e.printStackTrace()
  }
  def printEnclosing()(implicit
      line: sourcecode.Line,
      enclosing: sourcecode.Enclosing
  ): Unit = {
    val enclosingTrimmed =
      enclosing.value.split(' ').filter(_ != "$anonfun").mkString(" ")
    scribe.info(enclosingTrimmed)
  }
}
