package scala.meta.internal.pc.classpath

import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.PathMatcher
import java.nio.file.Paths

import scala.reflect.io.AbstractFile
import scala.tools.nsc.Reporting.WarningCategory
import scala.tools.nsc.symtab.BrowsingLoaders

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.pc.MetalsGlobal

/**
 * This class is based on the original presentation compiler implementation in 2.13.16, with a few tweaks inspired
 * by Hydra or prompted by usability issues in Metals. Differences are marked in code.
 */
abstract class MetalsBrowsingLoaders extends BrowsingLoaders {
  override val global: MetalsGlobal
  import global._

  private lazy val normalizedShimPatterns: List[String] =
    global.metalsConfig
      .shimGlobs()
      .asScala
      .toList
      .map(_.trim)
      .filter(!_.isEmpty())
      .map(toGlobPattern)

  private lazy val shimMatchers: List[PathMatcher] = {
    logger.debug(
      s"[shim] configured shim globs: ${normalizedShimPatterns.mkString("[", ", ", "]")}"
    )
    normalizedShimPatterns
      .map(pattern => FileSystems.getDefault.getPathMatcher(pattern))
  }

  private def toGlobPattern(pattern: String): String = {
    if (pattern.startsWith("glob:") || pattern.startsWith("regex:")) pattern
    else s"glob:$pattern"
  }

  private def matchesAnyShimPattern(path: String): Boolean = {
    shimMatchers.exists { matcher =>
      try matcher.matches(Paths.get(path))
      catch {
        case _: InvalidPathException => false
      }
    }
  }

  private def isShim(f: AbstractFile): Boolean = {
    (f ne null) && {
      val path = Option(f.path).getOrElse(f.name)
      matchesAnyShimPattern(path) || matchesAnyShimPattern(f.name)
    }
  }

  override protected def compileLate(srcfile: AbstractFile): Unit = {
    // Metals specific: track which sources were loaded from the source path to be able to prune them later
    PruneLateSourcesComponent.loadedFromSource.add(srcfile)
    super.compileLate(srcfile)
  }

  // Metals specific: harden around "inconsistent class/module pair" errors, see PLAT-146503
  override def enterClassAndModule(
      root: Symbol,
      name: TermName,
      getCompleter: (ClassSymbol, ModuleSymbol) => SymbolLoader
  ): Unit = {
    try {
      super.enterClassAndModule(root, name, getCompleter)
    } catch {
      case e: AssertionError =>
        logger.debug(s"Error entering class and module: ${e.getMessage}")
    }
  }

  /**
   * DATABRICKS: Copied form BrowsingLoaders.enterIfNew
   *
   * In browse mode, it can happen that an encountered symbol is already
   *  present. For instance, if the source file has a name different from
   *  the classes and objects it contains, the symbol loader will always
   *  reparse the source file. The symbols it encounters might already be loaded
   *  as class files. In this case we return the one which has a sourcefile
   *  (and the other has not), and issue an error if both have sourcefiles.
   */
  override protected def enterIfNew(
      owner: Symbol,
      member: Symbol,
      completer: SymbolLoader
  ): Symbol = {
    completer.sourcefile match {
      case Some(src) =>
        (if (member.isModule) member.moduleClass else member).associatedFile =
          src
      case _ =>
    }

    val decls = owner.info.decls
    val existing = decls.lookup(member.name)
    if (isShim(member.associatedFile))
      logger.debug(
        s"[shim] Attempting to load $member from shim ${member.associatedFile}"
      )

    if (existing == NoSymbol) {
      decls enter member
      return member
    }

    // there's an existing symbol, special-case shims when deciding whether to keep or replace
    if (isShim(member.sourceFile)) {
      logger.debug(
        s"[shim] Refusing to load $member from ${member.sourceFile}, preferring existing ${existing.associatedFile}"
      )
      return existing
    }

    val existingSourceFile = existing.sourceFile

    if (isShim(existing.associatedFile))
      logger.debug(
        s"[shim] Replacing $member from existing shim, preferring new ${existing.associatedFile}"
      )

    if (existingSourceFile == null || isShim(existing.associatedFile)) {
      decls unlink existing
      decls enter member
      member
    } else {
      val memberSourceFile = member.sourceFile
      if (memberSourceFile != null && existingSourceFile != memberSourceFile)
        globalError(
          s"${member}is defined twice,\n in $existingSourceFile\n and also in $memberSourceFile"
        )
      existing
    }
  }

  override def browseTopLevel(root: Symbol, src: AbstractFile): Unit = {

    class BrowserTraverser extends Traverser {
      var packagePrefix = ""
      var entered = 0
      def addPackagePrefix(pkg: Tree): Unit = pkg match {
        case Select(pre, name) =>
          addPackagePrefix(pre)
          packagePrefix += ("." + name)
        case Ident(name) =>
          if (name != nme.EMPTY_PACKAGE_NAME) { // mirrors logic in Namers, see createPackageSymbol
            if (packagePrefix.length != 0) packagePrefix += "."
            packagePrefix += name
          }
        case _ =>
          throw new syntaxAnalyzer.MalformedInput(
            pkg.pos.point,
            "illegal tree node in package prefix: " + pkg
          )
      }

      private def inPackagePrefix(pkg: Tree)(op: => Unit): Unit = {
        val oldPrefix = packagePrefix
        addPackagePrefix(pkg)
        op
        packagePrefix = oldPrefix
      }

      override def traverse(tree: Tree): Unit = tree match {
        case PackageDef(pid, stats) =>
          inPackagePrefix(pid) { stats.foreach(traverse) }

        case ClassDef(mods, name, _, _) =>
          if (packagePrefix == root.fullName) {
            enterClass(root, name.toString, new SourcefileLoader(src))
            if (mods.isCase) {
              // Metals specific: we always enter the companion object for case classes (Hydra does the same)
              // for our specific case, see PLAT-148990
              enterModule(root, name.toString, new SourcefileLoader(src))
              entered += 1
            }
            entered += 1
          } else
            log("prefixes differ: " + packagePrefix + "," + root.fullName)
        case ModuleDef(_, name, _) =>
          if (packagePrefix == root.fullName) {
            val module =
              enterModule(root, name.toString, new SourcefileLoader(src))
            entered += 1
            if (name == nme.PACKAGEkw) {
              log("open package module: " + module)
              openPackageModule(module, root)
            }
          } else
            log("prefixes differ: " + packagePrefix + "," + root.fullName)
        case _ =>
      }
    }

    val source = getSourceFile(src) // this uses the current encoding
    val body = new syntaxAnalyzer.OutlineParser(source) {
      // needed for deprecation warnings that are emitted by the scanner, and that
      // go by default to global.currentUnit, which may be different from the unit
      // we are parsing here
      override val unit: CompilationUnit = new CompilationUnit(this.source)
    }.parse()

    val browser = new BrowserTraverser
    browser.traverse(body)
    if (browser.entered == 0)
      runReporting.warning(
        NoPosition,
        "No classes or objects found in " + source + " that go in " + root,
        WarningCategory.OtherDebug,
        site = ""
      )
  }
}
