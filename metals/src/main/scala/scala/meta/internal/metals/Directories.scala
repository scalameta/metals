package scala.meta.internal.metals
import scala.meta.io.RelativePath

object Directories {
  def database: RelativePath =
    RelativePath(".metals").resolve("metals.h2.db")
  def hiddenScalafmt: RelativePath =
    RelativePath(".metals").resolve(".scalafmt.conf")
  def readonly: RelativePath =
    RelativePath(".metals").resolve("readonly")
  def tmp: RelativePath =
    RelativePath(".metals").resolve(".tmp")
  def dependencies: RelativePath =
    readonly.resolve(dependenciesName)
  def log: RelativePath =
    RelativePath(".metals").resolve("metals.log")
  def semanticdb: RelativePath =
    RelativePath("META-INF").resolve("semanticdb")
  def bestEffort: RelativePath =
    RelativePath("META-INF").resolve("best-effort")
  def pc: RelativePath =
    RelativePath(".metals").resolve("pc.log")
  def workspaceSymbol: RelativePath =
    RelativePath(".metals").resolve("workspace-symbol.md")
  def stacktrace: RelativePath =
    RelativePath(".metals").resolve(stacktraceFilename)
  def bazelBsp: RelativePath =
    RelativePath(".bazelbsp")
  def bsp: RelativePath =
    RelativePath(".bsp")
  def metalsSettings: RelativePath =
    RelativePath(".metals").resolve("settings.json")
  def javaHeaderCompiler: RelativePath =
    RelativePath(".metals").resolve("lib").resolve("java-header-compiler.jar")
  def outDir: RelativePath =
    RelativePath(".metals").resolve("out")

  val stacktraceFilename = "stacktrace.scala"
  val dependenciesName = "dependencies"
}
