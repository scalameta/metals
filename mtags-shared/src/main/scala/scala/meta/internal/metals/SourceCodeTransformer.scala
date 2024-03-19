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

    // Computes all interim packages with exclusion of the top level one
    // E.g. foo.bar.baz produces Seq("foo.bar", "foo.bar.baz")
    private def interimPackages(packageName: String): Seq[String] = {
      val segments = packageName.split('.')
      require(segments.nonEmpty, s"Invalid package name $packageName")
      val minPackageSegments = 2
      segments.toList
        .drop(minPackageSegments)
        .scanLeft(segments.take(minPackageSegments).mkString("."))(_ + "." + _)
    }
    // All Scala 2.13/3 stdlib packages
    private val commonScalaPackages = Seq(
      "scala.annotation", "scala.beans", "scala.collection.concurrent",
      "scala.collection.convert", "scala.collection.generic",
      "scala.collection.immutable", "scala.collection.mutable",
      "scala.collection.compat", "scala.compiletime.ops",
      "scala.compiletime.testing", "scala.concurrent.duration",
      "scala.deriving", "scala.io", "scala.jdk.javaapi", "scala.math",
      "scala.quoted.runtime", "scala.ref", "scala.reflect", "scala.runtime",
      "scala.sys.process", "scala.util.control", "scala.util.hashing",
      "scala.util.matching"
    ).flatMap(interimPackages).distinct

    // All java.base packages
    private val commonJavaPackages = Seq(
      "java.io", "java.lang", "java.lang.annotation", "java.lang.constant",
      "java.lang.foreign", "java.lang.invoke", "java.lang.module",
      "java.lang.ref", "java.lang.reflect", "java.lang.runtime", "java.math",
      "java.net", "java.net.spi", "java.nio", "java.nio.channels",
      "java.nio.channels.spi", "java.nio.charset", "java.nio.charset.spi",
      "java.nio.file", "java.nio.file.attribute", "java.nio.file.spi",
      "java.security", "java.security.cert", "java.security.interfaces",
      "java.security.spec", "java.text", "java.text.spi", "java.time",
      "java.time.chrono", "java.time.format", "java.time.temporal",
      "java.time.zone", "java.util", "java.util.concurrent",
      "java.util.concurrent.atomic", "java.util.concurrent.locks",
      "java.util.function", "java.util.jar", "java.util.random",
      "java.util.regex", "java.util.spi", "java.util.stream", "java.util.zip",
      "javax.crypto", "javax.crypto.interfaces", "javax.crypto.spec",
      "javax.net", "javax.net.ssl", "javax.security.auth",
      "javax.security.auth.callback", "javax.security.auth.login",
      "javax.security.auth.spi", "javax.security.auth.x500",
      "javax.security.cert"
    ).flatMap(interimPackages).distinct

    private val wellKnownPackages = commonScalaPackages ++ commonJavaPackages
    def isWellKnownPackageSelector(v: String): Boolean = {
      val normalized = v.stripPrefix("_root_.")
      wellKnownPackages.exists(normalized.startsWith(_))
    }

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
      }
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
        if (fillInLength < 0) prefix.tail.dropRight(-fillInLength)
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
