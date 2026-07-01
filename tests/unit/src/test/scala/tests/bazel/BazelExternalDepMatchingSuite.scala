package tests.bazel

import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelMbtImporter

import tests.BaseSuite

/**
 * Matching of `bazel query` external dependency labels to the imported Maven
 * [[MbtDependencyModule]]s by [[BazelMbtImporter.matchExternalDepsToModules]].
 *
 * The match keys on the `rules_jvm_external` coordinate alias target suffix
 * (`//:<group>_<artifact>`), independent of the Maven hub's apparent repository
 * name and of whether `bazel query` renders the hub apparently (`@maven//...`)
 * or canonically (`@@rules_jvm_external++maven+maven//...`).
 */
class BazelExternalDepMatchingSuite extends BaseSuite {

  private def module(id: String): MbtDependencyModule =
    MbtDependencyModule(id, s"file:///jars/$id.jar", null)

  private val guava = module("com.google.guava:guava:33.6.0-jre")
  private val jspecify = module("org.jspecify:jspecify:1.0.0")
  private val modules = Seq(guava, jspecify)

  private def matchOne(label: String): List[String] =
    BazelMbtImporter
      .matchExternalDepsToModules(Map("//app:app" -> List(label)), modules)
      .getOrElse("//app:app", Nil)

  test("apparent-hub-default-name") {
    assertEquals(matchOne("@maven//:com_google_guava_guava"), List(guava.id))
  }

  test("apparent-hub-aliased-name") {
    // `use_repo(maven, mvn = "maven")` makes BUILD files reference `@mvn//:...`
    // while the maven_install is still named "maven". The match must not depend
    // on the apparent repository name.
    assertEquals(matchOne("@mvn//:com_google_guava_guava"), List(guava.id))
  }

  test("canonical-hub-bzlmod-plus") {
    assertEquals(
      matchOne("@@rules_jvm_external++maven+maven//:com_google_guava_guava"),
      List(guava.id),
    )
  }

  test("canonical-hub-bzlmod-tilde") {
    assertEquals(
      matchOne("@@rules_jvm_external~~maven~maven//:com_google_guava_guava"),
      List(guava.id),
    )
  }

  test("per-artifact-backing-repo-does-not-match") {
    // The canonical per-artifact repo carries a `//jar:jar` target, never the
    // bare coordinate alias, so it must not match.
    assertEquals(
      matchOne(
        "@@rules_jvm_external++maven+com_google_guava_guava_33_6_0_jre//jar:jar"
      ),
      Nil,
    )
  }

  test("non-maven-external-does-not-match") {
    assertEquals(matchOne("@platforms//os:linux"), Nil)
  }

  test("dedups-same-coordinate-from-multiple-apparent-names") {
    val matched = BazelMbtImporter.matchExternalDepsToModules(
      Map(
        "//app:app" -> List(
          "@maven//:com_google_guava_guava",
          "@maven//:org_jspecify_jspecify",
          "@mvn//:com_google_guava_guava",
        )
      ),
      modules,
    )
    assertEquals(
      matched("//app:app").sorted,
      List(guava.id, jspecify.id).sorted,
    )
  }
}
