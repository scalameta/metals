package scala.meta.internal.pc.classpath

import scala.reflect.io.AbstractFile
import scala.tools.nsc.Reporting.WarningCategory
import scala.tools.nsc.symtab.BrowsingLoaders

import scala.meta.internal.pc.MetalsGlobal

/**
 * This class is based on the original presentation compiler implementation in 2.13.16, with a few tweaks inspired
 * by Hydra or prompted by usability issues in Metals. Differences are marked in code.
 */
abstract class MetalsBrowsingLoaders extends BrowsingLoaders {
  override val global: MetalsGlobal
  import global._

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
    val body = new syntaxAnalyzer.OutlineParser(source).parse()
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
