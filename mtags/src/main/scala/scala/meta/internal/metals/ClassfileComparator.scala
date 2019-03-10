package scala.meta.internal.metals

class ClassfileComparator(query: String)
    extends java.util.Comparator[Classfile] {
  def characterCount(string: String, ch: Char): Int = {
    var i = 0
    var count = 0
    while (i < string.length) {
      if (string.charAt(i) == ch) {
        count += 1
      }
      i += 1
    }
    count
  }
  override def compare(o1: Classfile, o2: Classfile): Int = {
    val byNameLength = Integer.compare(
      Fuzzy.nameLength(o1.filename),
      Fuzzy.nameLength(o2.filename)
    )
    if (byNameLength != 0) byNameLength
    else {
      val byInnerclassDepth = Integer.compare(
        characterCount(o1.filename, '$'),
        characterCount(o2.filename, '$')
      )
      if (byInnerclassDepth != 0) byInnerclassDepth
      else {
        val byFirstQueryCharacter = Integer.compare(
          o1.filename.indexOf(query.head),
          o2.filename.indexOf(query.head)
        )
        if (byFirstQueryCharacter != 0) {
          byFirstQueryCharacter
        } else {
          val byPackageDepth = Integer.compare(
            characterCount(o1.pkg, '/'),
            characterCount(o2.pkg, '/')
          )
          if (byPackageDepth != 0) byPackageDepth
          else o1.filename.compareTo(o2.filename)
        }
      }
    }
  }
}
