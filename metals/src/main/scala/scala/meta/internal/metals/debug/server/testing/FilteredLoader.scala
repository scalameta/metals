package scala.meta.internal.metals.debug.server.testing

/**
 * Delegates class loading to `parent` for all classes included by `filter`.  An attempt to load classes excluded by `filter`
 * results in a `ClassNotFoundException`.
 */
final class FilteredLoader(parent: ClassLoader, filter: IncludeClassFilter)
    extends ClassLoader(parent) {
  require(
    parent != null
  ) // included because a null parent is legitimate in Java

  @throws(classOf[ClassNotFoundException])
  override final def loadClass(
      className: String,
      resolve: Boolean,
  ): Class[_] = {
    if (filter.include(className))
      super.loadClass(className, resolve)
    else
      throw new ClassNotFoundException(className)
  }
}

class IncludeClassFilter(packages: Set[String]) {
  def include(className: String): Boolean =
    packages.exists(className.startsWith)
}
