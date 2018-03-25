package scala.meta.metals

/**
 * The Metals effects.
 *
 * Observable[Unit] is not descriptive of what the observable represents.
 * Instead, we create Unit-like types to better document what effects are
 * flowing through our application.
 */
sealed abstract class Effects
object Effects {
  final class IndexSemanticdb extends Effects
  final val IndexSemanticdb = new IndexSemanticdb
  final class IndexSourcesClasspath extends Effects
  final val IndexSourcesClasspath = new IndexSourcesClasspath
  final class InstallPresentationCompiler extends Effects
  final val InstallPresentationCompiler = new InstallPresentationCompiler
  final class PublishDiagnostics extends Effects
  final val PublishDiagnostics = new PublishDiagnostics
  final class UpdateBuffers extends Effects
  final val UpdateBuffers = new UpdateBuffers
}
