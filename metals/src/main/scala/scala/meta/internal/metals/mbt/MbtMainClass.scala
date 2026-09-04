package scala.meta.internal.metals.mbt

import javax.annotation.Nullable

/**
 * A runnable main class declared by the build system.
 *
 * @param className fully-qualified main class name
 * @param configuration build-system id of the runnable that executes this
 *   class. For Bazel this is the `java_binary` / `scala_binary` target
 *   label, matching an entry in [[MbtNamespace.configurations]].
 */
case class MbtMainClass(
    className: String,
    @Nullable configuration: String = null,
)
