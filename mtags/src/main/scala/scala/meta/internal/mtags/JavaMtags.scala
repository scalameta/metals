package scala.meta.internal.mtags

import java.net.URI
import java.util.Optional

import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.Report
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property
import scala.meta.pc.reports.ReportContext

import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSTree
import org.treesitter.TreeSitterJava

// Tree-sitter node type reference:
// https://github.com/tree-sitter/tree-sitter-java/blob/master/src/node-types.json
object JavaMtags {
  // ThreadLocal ensures thread safety. Re-entrancy on the same thread is not
  // expected — JavaMtags.indexRoot() is always the top-level entry point.
  private val threadLocalParser: ThreadLocal[TSParser] =
    ThreadLocal.withInitial { () =>
      val parser = new TSParser()
      parser.setLanguage(new TreeSitterJava())
      parser
    }

  def index(
      input: Input.VirtualFile,
      includeMembers: Boolean
  )(implicit rc: ReportContext): MtagsIndexer =
    new JavaMtags(input, includeMembers)
}

class JavaMtags(virtualFile: Input.VirtualFile, includeMembers: Boolean)(
    implicit rc: ReportContext
) extends MtagsIndexer { self =>
  override def language: Language = Language.JAVA

  override def input: Input.VirtualFile = virtualFile

  override def indexRoot(): Unit = {
    val parser = JavaMtags.threadLocalParser.get()
    var tree: TSTree = null
    try {
      // First arg is old tree for incremental re-parsing; null for fresh parse
      tree = parser.parseString(null, input.value)
      val root = tree.getRootNode()
      walkProgram(root)
    } catch {
      case NonFatal(e) =>
        reportError("tree-sitter parse error", e)
    } finally {
      if (tree != null) tree.close()
    }
  }

  private def walkProgram(root: TSNode): Unit = {
    val childCount = root.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = root.getNamedChild(i)
      child.getType() match {
        case "package_declaration" =>
          walkPackageDeclaration(child)
        case "class_declaration" | "interface_declaration" |
            "enum_declaration" | "record_declaration" |
            "annotation_type_declaration" =>
          visitClassNode(child)
        case _ => // skip imports, comments at top level, etc.
      }
      i += 1
    }
  }

  private def walkPackageDeclaration(node: TSNode): Unit = {
    val nameNode = findPackageName(node)
    if (nameNode != null) {
      val parts = extractScopedIdentifierParts(nameNode)
      parts.foreach { case (name, startPoint, endPoint) =>
        pkg(
          name,
          input.toPosition(
            startPoint.getRow(),
            startPoint.getColumn(),
            endPoint.getRow(),
            endPoint.getColumn()
          )
        )
      }
    }
  }

  private def findPackageName(pkgNode: TSNode): TSNode = {
    val childCount = pkgNode.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = pkgNode.getNamedChild(i)
      val tpe = child.getType()
      if (tpe == "scoped_identifier" || tpe == "identifier") return child
      i += 1
    }
    null
  }

  private def extractScopedIdentifierParts(
      node: TSNode
  ): List[(String, org.treesitter.TSPoint, org.treesitter.TSPoint)] = {
    node.getType() match {
      case "identifier" =>
        val text = nodeText(node)
        List((text, node.getStartPoint(), node.getEndPoint()))
      case "scoped_identifier" =>
        val scope = node.getChildByFieldName("scope")
        val name = node.getChildByFieldName("name")
        val scopeParts =
          if (scope != null && !scope.isNull())
            extractScopedIdentifierParts(scope)
          else Nil
        val nameParts =
          if (name != null && !name.isNull()) {
            val text = nodeText(name)
            List((text, name.getStartPoint(), name.getEndPoint()))
          } else Nil
        scopeParts ++ nameParts
      case _ => Nil
    }
  }

  def visitClass(
      info: JavaClassInfo,
      pos: Position,
      kind: Kind
  ): Unit = {
    tpe(
      info.name,
      pos,
      kind,
      if (info.isEnum) Property.ENUM.value else 0
    )
  }

  private def visitClassNode(node: TSNode): Unit =
    withOwner(owner) {
      val nameNode = node.getChildByFieldName("name")
      if (nameNode != null && !nameNode.isNull()) {
        val name = nodeText(nameNode)
        val nodeType = node.getType()
        val isInterface = nodeType == "interface_declaration" ||
          nodeType == "annotation_type_declaration"
        val isEnum = nodeType == "enum_declaration"
        val kind = if (isInterface) Kind.INTERFACE else Kind.CLASS
        val pos = nodePosition(nameNode)
        val javadoc = findJavadocComment(node)
        val typeParams = extractTypeParameterNames(node)

        val info = JavaClassInfo(
          name = name,
          javadocComment = javadoc,
          typeParameterNames = typeParams,
          isInterface = isInterface,
          isEnum = isEnum
        )

        visitClass(info, pos, kind)

        // Visit nested classes and members
        val body = node.getChildByFieldName("body")
        if (body != null && !body.isNull()) {
          visitNestedClasses(body)
          if (includeMembers) {
            visitMethods(body)
            visitConstructors(body, name)
            visitFields(body, isEnum)
            // For enums, methods/constructors/nested classes may be in
            // enum_body_declarations
            visitEnumBodyDeclarations(body, name)
          }
        }
      }
    }

  private def visitNestedClasses(body: TSNode): Unit = {
    val childCount = body.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = body.getNamedChild(i)
      child.getType() match {
        case "class_declaration" | "interface_declaration" |
            "enum_declaration" | "record_declaration" |
            "annotation_type_declaration" =>
          visitClassNode(child)
        case "enum_body_declarations" =>
          visitNestedClasses(child)
        case _ =>
      }
      i += 1
    }
  }

  private def visitEnumBodyDeclarations(
      body: TSNode,
      className: String
  ): Unit = {
    val childCount = body.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = body.getNamedChild(i)
      if (child.getType() == "enum_body_declarations") {
        visitMethods(child)
        visitConstructors(child, className)
        visitFields(child, isEnum = false)
      }
      i += 1
    }
  }

  def visitConstructor(
      info: JavaConstructorInfo,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    super.ctor(disambiguator, pos, properties)
  }

  private def visitConstructors(body: TSNode, className: String): Unit = {
    val overloads = new OverloadDisambiguator()
    val childCount = body.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = body.getNamedChild(i)
      val childType = child.getType()
      // compact_constructor_declaration is used in Java records
      if (
        childType == "constructor_declaration" ||
        childType == "compact_constructor_declaration"
      ) {
        if (!isPrivateNode(child)) {
          val disambiguator = overloads.disambiguator(className)
          val nameNode = child.getChildByFieldName("name")
          val pos =
            if (nameNode != null && !nameNode.isNull()) nodePosition(nameNode)
            else nodePosition(child)
          val javadoc = findJavadocComment(child)
          val paramNames = extractParameterNames(child)
          val typeParams = extractTypeParameterNames(child)

          val info = JavaConstructorInfo(
            name = className,
            javadocComment = javadoc,
            parameterNames = paramNames,
            typeParameterNames = typeParams
          )

          withOwner() {
            visitConstructor(info, disambiguator, pos, 0)
          }
        }
      }
      i += 1
    }
  }

  def visitMethod(
      info: JavaMethodInfo,
      name: String,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    super.method(name, disambiguator, pos, properties)
  }

  private def visitMethods(body: TSNode): Unit = {
    val overloads = new OverloadDisambiguator()
    // Collect methods, sort static last for overload disambiguation compatibility
    val methods = collectMethods(body)
    val sorted = methods.sortWith { case ((_, isStatic1), (_, isStatic2)) =>
      java.lang.Boolean.compare(isStatic1, isStatic2) < 0
    }

    sorted.foreach { case (methodNode, _) =>
      val nameNode = methodNode.getChildByFieldName("name")
      if (nameNode != null && !nameNode.isNull()) {
        val name = nodeText(nameNode)
        val disambiguator = overloads.disambiguator(name)
        val pos = nodePosition(nameNode)
        val javadoc = findJavadocComment(methodNode)
        val paramNames = extractParameterNames(methodNode)
        val typeParams = extractTypeParameterNames(methodNode)
        val isStatic = hasModifier(methodNode, "static")

        val info = JavaMethodInfo(
          name = name,
          javadocComment = javadoc,
          parameterNames = paramNames,
          typeParameterNames = typeParams,
          isStatic = isStatic
        )

        withOwner() {
          visitMethod(info, name, disambiguator, pos, 0)
        }
      }
    }
  }

  private def collectMethods(
      body: TSNode
  ): List[(TSNode, Boolean)] = {
    val builder = List.newBuilder[(TSNode, Boolean)]
    val childCount = body.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = body.getNamedChild(i)
      child.getType() match {
        case "method_declaration" =>
          val isStatic = hasModifier(child, "static")
          builder += ((child, isStatic))
        case "annotation_type_element_declaration" =>
          // Annotation type elements (e.g. `String value() default ""`)
          // are method-like declarations in @interface types
          builder += ((child, false))
        case _ =>
      }
      i += 1
    }
    builder.result()
  }

  private def visitFields(body: TSNode, isEnum: Boolean): Unit = {
    val childCount = body.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = body.getNamedChild(i)
      child.getType() match {
        case "field_declaration" | "constant_declaration" =>
          // constant_declaration appears in interfaces for constants
          visitFieldDeclaration(child)
        case "enum_constant" if isEnum =>
          visitEnumConstant(child)
        case _ =>
      }
      i += 1
    }
  }

  private def visitFieldDeclaration(node: TSNode): Unit = {
    // field_declaration has "declarator" children which are variable_declarators
    val childCount = node.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = node.getNamedChild(i)
      if (child.getType() == "variable_declarator") {
        val nameNode = child.getChildByFieldName("name")
        if (nameNode != null && !nameNode.isNull()) {
          val name = nodeText(nameNode)
          val pos = nodePosition(nameNode)
          withOwner(owner) {
            term(name, pos, Kind.FIELD, 0)
          }
        }
      }
      i += 1
    }
  }

  private def visitEnumConstant(node: TSNode): Unit = {
    val nameNode = node.getChildByFieldName("name")
    if (nameNode != null && !nameNode.isNull()) {
      val name = nodeText(nameNode)
      val pos = nodePosition(nameNode)
      withOwner(owner) {
        term(name, pos, Kind.FIELD, Property.ENUM.value)
      }
    }
  }

  // --- Helper methods ---

  // Lazily compute UTF-8 bytes for proper byte-offset-based text extraction
  private lazy val utf8Bytes: Array[Byte] =
    input.value.getBytes("UTF-8")

  protected def nodeText(node: TSNode): String = {
    val startByte = node.getStartByte()
    val endByte = node.getEndByte()
    new String(utf8Bytes, startByte, endByte - startByte, "UTF-8")
  }

  protected def nodePosition(node: TSNode): Position = {
    val start = node.getStartPoint()
    val end = node.getEndPoint()
    val startCharCol = byteColumnToCharColumn(
      node.getStartByte(),
      start.getColumn()
    )
    val endCharCol = byteColumnToCharColumn(
      node.getEndByte(),
      end.getColumn()
    )
    input.toPosition(
      start.getRow(),
      startCharCol,
      end.getRow(),
      endCharCol
    )
  }

  // TSPoint.getColumn() returns byte offsets from line start in UTF-8.
  // input.toPosition expects character offsets. Convert by decoding the
  // leading bytes on the line to count characters.
  private def byteColumnToCharColumn(
      nodeByte: Int,
      byteColumn: Int
  ): Int = {
    if (byteColumn == 0) return 0
    val lineStartByte = nodeByte - byteColumn
    new String(utf8Bytes, lineStartByte, byteColumn, "UTF-8").length
  }

  protected def findJavadocComment(node: TSNode): Option[String] = {
    var sibling = node.getPrevSibling()
    // Skip annotations and line comments to find the Javadoc block_comment
    while (sibling != null && !sibling.isNull()) {
      sibling.getType() match {
        case "block_comment" =>
          val text = nodeText(sibling)
          if (text.startsWith("/**")) return Some(text)
          else return None
        case "line_comment" | "marker_annotation" | "annotation" =>
          sibling = sibling.getPrevSibling()
        case _ =>
          return None
      }
    }
    None
  }

  private def isPrivateNode(node: TSNode): Boolean =
    hasModifier(node, "private")

  private def hasModifier(node: TSNode, modifier: String): Boolean = {
    val childCount = node.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = node.getNamedChild(i)
      if (child.getType() == "modifiers") {
        // Modifiers like "static", "private" are unnamed token children
        val totalCount = child.getChildCount()
        var k = 0
        while (k < totalCount) {
          val mod = child.getChild(k)
          if (nodeText(mod) == modifier) return true
          k += 1
        }
        return false
      }
      i += 1
    }
    false
  }

  protected def extractParameterNames(node: TSNode): List[String] = {
    val params = node.getChildByFieldName("parameters")
    if (params == null || params.isNull()) return Nil
    val builder = List.newBuilder[String]
    val childCount = params.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = params.getNamedChild(i)
      child.getType() match {
        case "formal_parameter" =>
          val nameNode = child.getChildByFieldName("name")
          if (nameNode != null && !nameNode.isNull()) {
            builder += nodeText(nameNode)
          }
        case "spread_parameter" =>
          // Varargs: name is in a variable_declarator or direct identifier child
          val nameNode = child.getChildByFieldName("name")
          if (nameNode != null && !nameNode.isNull()) {
            builder += nodeText(nameNode)
          } else {
            // Fallback: find variable_declarator or identifier child
            val paramName = findSpreadParamName(child)
            if (paramName != null) builder += paramName
          }
        case _ =>
      }
      i += 1
    }
    builder.result()
  }

  private def findSpreadParamName(spreadParam: TSNode): String = {
    val childCount = spreadParam.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = spreadParam.getNamedChild(i)
      child.getType() match {
        case "variable_declarator" =>
          // variable_declarator contains the name as its text
          val nameNode = child.getChildByFieldName("name")
          if (nameNode != null && !nameNode.isNull()) return nodeText(nameNode)
          else return nodeText(child)
        case "identifier" =>
          // Check if this is the parameter name (not the type)
          // Type is type_identifier, name is identifier
          return nodeText(child)
        case _ =>
      }
      i += 1
    }
    null
  }

  protected def extractTypeParameterNames(node: TSNode): List[String] = {
    val tparams = node.getChildByFieldName("type_parameters")
    if (tparams == null || tparams.isNull()) return Nil
    val builder = List.newBuilder[String]
    val childCount = tparams.getNamedChildCount()
    var i = 0
    while (i < childCount) {
      val child = tparams.getNamedChild(i)
      if (child.getType() == "type_parameter") {
        // The first named child of type_parameter is the type_identifier
        val nameCount = child.getNamedChildCount()
        if (nameCount > 0) {
          val nameNode = child.getNamedChild(0)
          builder += nodeText(nameNode)
        }
      }
      i += 1
    }
    builder.result()
  }

  private def reportError(
      errorName: String,
      e: Throwable
  ): Unit = {
    try {
      val shortFileName = {
        val index = virtualFile.path.indexOf("jar!")
        if (index > 0) virtualFile.path.substring(index + 4)
        else virtualFile.path
      }

      rc.unsanitized()
        .create(() =>
          new Report(
            name = "java-mtags-error",
            text = s"""|error in tree-sitter java parser
                       |""".stripMargin,
            error = Some(e),
            path = Optional.of(new URI(virtualFile.path)),
            shortSummary = s"Tree-sitter $errorName in $shortFileName",
            id = Optional.of(virtualFile.path)
          )
        )
    } catch {
      case NonFatal(_) =>
    }
  }
}
