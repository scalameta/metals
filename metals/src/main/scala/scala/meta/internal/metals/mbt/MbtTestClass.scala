package scala.meta.internal.metals.mbt

import javax.annotation.Nullable

/**
 * A test suite declared by the build system.
 *
 * @param className fully-qualified test class name
 * @param sourcePath optional workspace-relative path to the test class
 *   source file. When omitted, Metals cannot verify the class against
 *   sources and reports it as declared by the build.
 * @param configuration build-system id of the runnable that executes this
 *   class. For Bazel this is the `java_test` / `scala_test` target label,
 *   matching an entry in [[MbtNamespace.configurations]].
 * @param framework optional BSP-style framework name (`JUnit`, `ScalaTest`,
 *   `munit`, ...), inferred from the target's dependencies when known.
 */
case class MbtTestClass(
    className: String,
    @Nullable sourcePath: String = null,
    @Nullable configuration: String = null,
    @Nullable framework: String = null,
)
