package scala.meta.internal.metals

/**
 * ClassLoader that is used to reflectively invoke presentation compiler APIs.
 *
 * The presentation compiler APIs are compiled against exact Scala versions of the compiler
 * while Metals only runs in a single Scala version. In order to communicate between Metals and the
 * reflectively loaded compiler, this classloader shares a subset of Java classes that appear in
 * method signatures of the `PresentationCompiler` class.
 */
class PresentationCompilerClassLoader(parent: ClassLoader)
    extends ClassLoader(null) {
  override def findClass(name: String): Class[_] = {
    val isShared =
      name.startsWith("org.eclipse.lsp4j") ||
        name.startsWith("com.google.gson") ||
        name.startsWith("scala.meta.pc")
    if (isShared) {
      parent.loadClass(name)
    } else {
      throw new ClassNotFoundException(name)
    }
  }
}
