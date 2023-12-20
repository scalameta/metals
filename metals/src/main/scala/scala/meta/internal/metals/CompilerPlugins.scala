package scala.meta.internal.metals

import java.nio.file.Files

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal
import scala.xml.XML

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

/**
 * Responsible for disabling unsupported compiler plugins.
 *
 * Metals only uses the presentation compiler for limited features
 * - completions
 * - hover
 * - parameter hints
 * Compiler plugins that don't affect those features can be disabled, for example
 * WartRemover that only reports diagnostics. Diagnostics are already published from
 * the build, where all compiler plugins are enabled by default.
 *
 * Some compiler plugins change the semantics of the Scala language. Metals officially
 * only supports a hardcoded list of such compiler plugins:
 * - kind-projector, introduces new syntax for anonymous type lambdas
 * - better-monadic-for, changes for comprehension desugaring
 * The IntelliJ Scala plugin has custom support for these plugins.
 *
 * Notably, macro-paradise-plugin id disabled because it does not work with the -Ymacro-expand:discard
 * setting making completions always fail when inside an expanded tree.
 *
 * The process for adding support for other compiler plugins is the following,
 * send a PR to Metals adding integration tests to demonstrate thee compiler plugin
 * - needs to be enabled in order for completions/hover to function. Many plugins
 *   like WartRemover that only use the compiler report don't need to be enabled.
 * - enabling the plugin does not break the presentation compiler in unexpected ways.
 */
class CompilerPlugins {
  private val cache = TrieMap.empty[Seq[AbsolutePath], Boolean]

  def filterSupportedOptions(options: Seq[String]): Seq[String] = {
    options.filter {
      case s"-Xplugin:$pathsString" =>
        val paths = pathsString.split(",").map(AbsolutePath(_)).toSeq
        cache.getOrElseUpdate(paths, isSupportedPlugin(paths))
      case s"-P:$plugin:$_" =>
        supportedPlugins.contains(plugin)
      case _ => true
    }
  }

  private val supportedPlugins = Set(
    "kind-projector", // https://github.com/non/kind-projector
    "bm4", // https://github.com/oleg-py/better-monadic-for
    // Intentionally not supported:
    // "macro-paradise-plugin", see https://github.com/scalameta/metals/issues/622
  )

  private def isSupportedPlugin(paths: Seq[AbsolutePath]): Boolean = {
    paths.exists { path =>
      path.isJar && {
        try {
          FileIO.withJarFileSystem(path, create = false, close = true) { root =>
            val scalacPluginXml = root.resolve("scalac-plugin.xml").toNIO
            Files.exists(scalacPluginXml) && {
              val xml = XML.load(
                Files.newInputStream(scalacPluginXml)
              )
              val name = (xml \ "name").text
              supportedPlugins.contains(name)
            }
          }
        } catch {
          case NonFatal(e) =>
            scribe.error(path.toString(), e)
            false
        }
      }
    }
  }

}
