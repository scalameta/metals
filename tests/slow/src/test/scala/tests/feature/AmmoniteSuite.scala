package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

class Ammonite213Suite extends tests.BaseAmmoniteSuite(V.scala213)

// TODO https://github.com/scalameta/metals/issues/2392
class Ammonite212Suite extends tests.BaseAmmoniteSuite("2.12.12")
