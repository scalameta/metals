package kpc

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters._

import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.mtags.KotlinMtags
import scala.meta.io.AbsolutePath

import com.intellij.openapi.util.Disposer
import coursierapi.Dependency
import coursierapi.Fetch
import org.jetbrains.kotlin.cli.FrontendConfigurationKeysKt
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi._

/**
 * Assert the symbols emitted by KotlinMtags match the declarations that the
 * Kotlin parser finds in library sources. Overload suffixes and constructors
 * are left out of the comparison.
 */
class KotlinMtagsLibrarySuite extends munit.FunSuite {

  private val stdlibSources: AbsolutePath = AbsolutePath(
    Fetch
      .create()
      .withDependencies(
        Dependency.of(
          "org.jetbrains.kotlin",
          "kotlin-stdlib",
          kotlin.KotlinVersion.CURRENT.toString,
        )
      )
      .withClassifiers(Set("sources").asJava)
      .fetch()
      .asScala
      .find(_.getName.endsWith("-sources.jar"))
      .get
      .toPath
  )

  test(stdlibSources.toNIO.getFileName.toString) {
    val configuration = new CompilerConfiguration()
    FrontendConfigurationKeysKt.setExtensionsStorage(
      configuration,
      new CompilerPluginRegistrar.ExtensionStorage(),
    )
    val disposable = Disposer.newDisposable()
    val factory = new KtPsiFactory(
      KotlinCoreEnvironment
        .createForProduction(
          disposable,
          configuration,
          EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        .getProject,
      false,
    )
    try {
      FileIO.withJarFileSystem(stdlibSources, create = false) { root =>
        FileIO.listAllFilesRecursively(root).foreach { file =>
          if (file.toNIO.getFileName.toString.endsWith(".kt")) {
            val path = file.toString
            val text = new String(file.readAllBytes, StandardCharsets.UTF_8)
            val obtained = new KotlinMtags(
              Input.VirtualFile(path, text),
              includeMembers = true,
            )(EmptyReportContext)
              .index()
              .occurrences
              .filter(_.role.isDefinition)
              .map(o =>
                s"${o.range.get.startLine}:${withoutOverload(o.symbol)}"
              )
              .filterNot(_.contains("`<init>`"))
              .toSet
            val expected = parserSymbols(factory.createFile(path, text))
            assertNoDiff(
              obtained.toList.sorted.mkString("\n"),
              expected.toList.sorted.mkString("\n"),
              path,
            )
          }
        }
      }
    } finally Disposer.dispose(disposable)
  }

  private def withoutOverload(symbol: String): String =
    symbol.replaceAll("""\(\+\d+\)\.$""", "().")

  /** The symbols that `KotlinMtags` should give for `file`, as `line:symbol`. */
  private def parserSymbols(file: KtFile): Set[String] = {
    val text = file.getText
    val result = Set.newBuilder[String]
    def line(offset: Int): Int = text.substring(0, offset).count(_ == '\n')
    def emit(offset: Int, symbol: String): Unit =
      result += s"${line(offset)}:$symbol"
    def encode(name: String): String =
      if (
        name.nonEmpty && Character.isJavaIdentifierStart(name.head) &&
        name.forall(Character.isJavaIdentifierPart)
      ) name
      else s"`$name`"
    def property(
        offset: Int,
        owner: String,
        name: String,
        isVar: Boolean,
        inAnnotation: Boolean,
    ): Unit = {
      emit(offset, owner + encode(name) + ".")
      // A property `isOn` has the getter `isOn()` and the setter `setOn()`.
      val isBoolean =
        name.length > 2 && name.startsWith("is") && !name.charAt(2).isLower
      val getter =
        if (inAnnotation || isBoolean) name else "get" + name.capitalize
      val setter =
        if (isBoolean) "set" + name.substring(2) else "set" + name.capitalize
      emit(offset, owner + encode(getter) + "().")
      if (isVar)
        emit(offset, owner + encode(setter) + "().")
    }
    def walk(declarations: Seq[KtDeclaration], owner: String): Unit =
      declarations.foreach {
        case d: KtEnumEntry =>
          emit(d.getTextOffset, owner + encode(d.getName) + ".")
        case d: KtClassOrObject =>
          val name = Option(d.getName).orElse(d match {
            case o: KtObjectDeclaration if o.isCompanion => Some("Companion")
            case _ => None
          })
          name.foreach { n =>
            val symbol = owner + encode(n) + "#"
            emit(d.getTextOffset, symbol)
            val inAnnotation = d match {
              case c: KtClass => c.isAnnotation
              case _ => false
            }
            for (p <- d.getPrimaryConstructorParameters.asScala)
              if (p.hasValOrVar)
                property(
                  p.getTextOffset,
                  symbol,
                  p.getName,
                  p.isMutable,
                  inAnnotation,
                )
            walk(d.getDeclarations.asScala.toSeq, symbol)
          }
        case d: KtNamedFunction if d.getName != null =>
          emit(d.getTextOffset, owner + encode(d.getName) + "().")
        case d: KtProperty if d.getName != null =>
          property(d.getTextOffset, owner, d.getName, d.isVar, false)
        case d: KtTypeAlias if d.getName != null =>
          emit(d.getTextOffset, owner + encode(d.getName) + "#")
        case _ =>
      }
    val pkg = file.getPackageFqName.pathSegments().asScala
    val owner =
      if (pkg.isEmpty) "_empty_/"
      else pkg.map(n => encode(n.asString) + "/").mkString
    walk(file.getDeclarations.asScala.toSeq, owner)
    result.result()
  }
}
