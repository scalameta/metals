package scala.meta.internal.metals

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Random

// Needs to be implemented in user of the reporting, eg. MetalsLSPService using scalameta or compiler
trait SourceCodeTransformer[Context, Tree] {

  /** Try parse using any available dialects/contexts and return the context that can be used for validation of sanitized source */
  def parse(source: String): Option[(Context, Tree)]

  /** Parse once using dedicated context, used for validation */
  def parse(source: String, context: Context): Option[Tree]

  def toSourceString(value: Tree, ctx: Context): String
  def transformer: ASTTransformer

  trait ASTTransformer {
    protected type Name
    protected type TermName <: Name
    protected type TypeName <: Name
    protected type UnclasifiedName <: Name

    protected def toTermName(name: String): TermName
    protected def toTypeName(name: String): TypeName
    protected def toUnclasifiedName(name: String): UnclasifiedName
    protected def toSymbol(name: Name): String

    private final val SymbolToPrefixLength = 3
    private final val ShortSymbolLength = 2

    def sanitizeSymbols(tree: Tree): Option[Tree]

    protected def isCommonScalaName(name: Name): Boolean = {
      val symbol = toSymbol(name)
      SourceCodeTransformer.CommonNames.types.contains(symbol) ||
      SourceCodeTransformer.CommonNames.methods.contains(symbol)
    }

    def isScalaOrJavaSelector(v: String): Boolean =
      v.startsWith("scala.") || v.startsWith("java.")

    protected def sanitizeTermName(name: TermName): TermName =
      termNames.getOrElseUpdate(
        name,
        toTermName(sanitizeSymbolOf(toSymbol(name), termNames.size.toString))
      )

    protected def sanitizeTypeName(name: TypeName): TypeName =
      typeNames.getOrElseUpdate(
        name,
        toTypeName(sanitizeSymbolOf(toSymbol(name), typeNames.size.toString))
      )

    protected def sanitizeUnclasifiedName(
        name: UnclasifiedName
    ): UnclasifiedName =
      unclasifiedNames.getOrElseUpdate(
        name,
        toUnclasifiedName(
          sanitizeSymbolOf(toSymbol(name), unclasifiedNames.size.toString)
        )
      )

    protected def sanitizeStringLiteral(original: String): String =
      original.map(c => if (c.isLetterOrDigit) '-' else c)

    protected def santitizeScalaSymbol(original: scala.Symbol): scala.Symbol =
      scala.Symbol(
        toSymbol(
          sanitizeUnclasifiedName(toUnclasifiedName(original.name))
        )
      )

    private def cacheOf[T] = mutable.Map.empty[T, T]
    private val symbols = cacheOf[String]
    private val termNames = cacheOf[TermName]
    private val typeNames = cacheOf[TypeName]
    private val unclasifiedNames = cacheOf[UnclasifiedName]

    private def sanitizeSymbolOf(
        originalSymbol: String,
        suffix: => String
    ): String = symbols.getOrElseUpdate(
      originalSymbol, {
        if (originalSymbol.length() <= ShortSymbolLength) originalSymbol
        else generateSymbol(originalSymbol, suffix)
      }.ensuring(
        _.length() == originalSymbol.length(),
        "new symbol has different length then original"
      )
    )

    private def generateSymbol(
        originalSymbol: String,
        suffix: => String
    ): String =
      if (originalSymbol.forall(isAsciiLetterOrDigit))
        newSimpleSymbol(originalSymbol, suffix, fillChar = 'x')
      else
        newSymbolsWithSpecialCharacters(originalSymbol)

    private def isAsciiLetterOrDigit(c: Char) =
      (c >= 'a' && c <= 'z') ||
        (c >= 'A' && c <= 'Z') ||
        (c >= '0' && c <= '9')

    private def newSymbolsWithSpecialCharacters(
        originalSymbol: String
    ): String = {
      val rnd = new Random(originalSymbol.##)

      def nextAsciiLetter(original: Char) = {
        val c = (rnd.nextInt('z' - 'a') + 'a').toChar
        val next = if (original.isUpper) c.toUpper else c
        next match {
          case 'O' => 'P' // To easiliy distinquish O from 0
          case 'I' => 'J' // I vs l
          case 'l' => 'k' // l vs I
          case c => c
        }
      }
      @tailrec def generate(): String = {
        val newSymbol = originalSymbol.map(c =>
          if (!isAsciiLetterOrDigit(c)) c
          else if (c.isDigit) c
          else nextAsciiLetter(c)
        )
        if (symbols.values.exists(_ == newSymbol)) generate()
        else newSymbol
      }
      generate()
    }

    private def newSimpleSymbol(
        originalSymbol: String,
        suffix: String,
        fillChar: Char
    ): String = {
      val prefix = originalSymbol.take(SymbolToPrefixLength)
      val prefixHead =
        if (originalSymbol.head.isUpper) prefix.head.toUpper
        else prefix.head.toLower
      val fillInLength =
        originalSymbol.length() - prefix.length() - suffix.length()
      val prefixTail =
        if (fillInLength < 0) prefix.tail.take(-fillInLength)
        else prefix.tail

      val sb = new java.lang.StringBuilder(originalSymbol.length())
      sb.append(prefixHead)
      sb.append(prefixTail)
      0.until(fillInLength).foreach(_ => sb.append(fillChar))
      sb.append(suffix)
      sb.toString()
    }

  }
}

private object SourceCodeTransformer {
  object CommonNames {
    final val types: Seq[String] = Seq("Byte", "Short", "Int", "Long", "String",
      "Unit", "Nothing", "Class", "Option", "Some", "None", "List", "Nil",
      "Set", "Seq", "Array", "Vector", "Stream", "LazyList", "Map", "Future",
      "Try", "Success", "Failure", "mutable", "immutable")

    final val methods: Seq[String] = Seq("get", "getOrElse", "orElse", "map",
      "left", "right", "flatMap", "flatten", "apply", "unapply", "fold",
      "foldLeft", "foldRight", "reduce", "reduceLeft", "reduceRight", "scan",
      "scanLeft", "scanRight", "recover", "recoverWith", "size", "length",
      "exists", "contains", "forall", "value", "underlying", "classOf",
      "toOption", "toEither", "toLeft", "toRight", "toString", "to",
      "stripMargin", "empty")
  }
}
