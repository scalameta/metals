package scala.meta.internal.worksheets

/**
 * ClassLoader that is used to reflectively invoke mdoc for worksheet evaluation.
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
