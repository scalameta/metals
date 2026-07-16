package scala.meta.internal.metals.mbt

import java.nio.file.FileSystems

object JavaModuleInfoIndexFilter extends MbtIndexFilter {
  private val matcher = FileSystems.getDefault.getPathMatcher(
    "glob:**/module-info.java"
  )

  override def decide(candidate: MbtFileCandidate): MbtIndexDecision =
    if (matcher.matches(candidate.path.toNIO))
      MbtIndexDecision.Skip
    else
      MbtIndexDecision.Continue
}
