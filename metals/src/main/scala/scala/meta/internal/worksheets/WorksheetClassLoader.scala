package scala.meta.internal.worksheets

/**
 * ClassLoader that is used to reflectively invoke mdoc for worksheet evaluation.
 *
 * This classloader exists in order to support Scala 2.11/2.12/2.13 for
 * evaluating worksheets. The classes in the `mdoc.interfaces` are Java-only,
 * allowing the Metals server to call mdoc instances from different Scala
 * versions.
 */
class MdocClassLoader(parent: ClassLoader) extends ClassLoader(null) {
  override def findClass(name: String): Class[_] = {
    val isShared = name.startsWith("mdoc.interfaces")
    if (isShared) {
      parent.loadClass(name)
    } else {
      throw new ClassNotFoundException(name)
    }
  }
}
