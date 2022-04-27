package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}

trait MetalsBuildServer
    extends b.BuildServer
    with b.ScalaBuildServer
    with b.JavaBuildServer
