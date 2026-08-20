package scala.meta.internal.metals.mbt

import javax.annotation.Nullable

/**
 * A test suite declared by the build system.
 *
 * @param className fully-qualified test class name
 * @param configuration build-system id of the runnable that executes this
 *   class. For Bazel this is the `java_test` / `scala_test` target label,
 *   matching an entry in [[MbtNamespace.configurations]].
 * @param framework optional BSP-style framework name (`JUnit`, `ScalaTest`,
 *   `munit`, ...), inferred from the target's dependencies when known.
 */
case class MbtTestClass(
    className: String,
    @Nullable configuration: String = null,
    @Nullable framework: String = null,
)
