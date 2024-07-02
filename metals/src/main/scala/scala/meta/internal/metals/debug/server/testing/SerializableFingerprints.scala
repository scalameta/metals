package sbt

import java.io.Serializable

import sbt.testing.AnnotatedFingerprint
import sbt.testing.Fingerprint
import sbt.testing.SubclassFingerprint

/**
 * This object is there only to instantiate the subclasses of Fingerprint that are
 * expected by the remote test runner (they're package private in sbt)
 */
object SerializableFingerprints {
  // Copied from ForkTests.scala in sbt
  def forkFingerprint(f: Fingerprint): Fingerprint with Serializable =
    f match {
      case s: SubclassFingerprint => new ForkMain.SubclassFingerscan(s)
      case a: AnnotatedFingerprint => new ForkMain.AnnotatedFingerscan(a)
      case _ => sys.error("Unknown fingerprint type: " + f.getClass)
    }
}
