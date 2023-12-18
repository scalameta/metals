package scala.meta.internal.builds

trait VersionRecommendation { self: BuildTool =>
  def minimumVersion: String
  def recommendedVersion: String
  def version: String
}
