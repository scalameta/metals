package scala.meta.internal.mtags

/**
 * Lightweight case classes carrying extracted data from Java declarations.
 * These decouple JavadocIndexer from the tree-sitter node model.
 */
sealed trait JavaDeclarationInfo {
  def name: String
  def javadocComment: Option[String]
  def parameterNames: List[String]
  def typeParameterNames: List[String]
}

case class JavaClassInfo(
    name: String,
    javadocComment: Option[String],
    typeParameterNames: List[String],
    isInterface: Boolean,
    isEnum: Boolean
) extends JavaDeclarationInfo {
  val parameterNames: List[String] = Nil
}

case class JavaMethodInfo(
    name: String,
    javadocComment: Option[String],
    parameterNames: List[String],
    typeParameterNames: List[String],
    isStatic: Boolean
) extends JavaDeclarationInfo

case class JavaConstructorInfo(
    name: String,
    javadocComment: Option[String],
    parameterNames: List[String],
    typeParameterNames: List[String],
    isPrivate: Boolean
) extends JavaDeclarationInfo

case class JavaFieldInfo(
    name: String,
    javadocComment: Option[String],
    isEnumConstant: Boolean
) extends JavaDeclarationInfo {
  val parameterNames: List[String] = Nil
  val typeParameterNames: List[String] = Nil
}
