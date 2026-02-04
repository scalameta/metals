package com.google.turbine.testing

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Objects.requireNonNull

object TestResources {
  def getResource(clazz: Class[_], resource: String): String =
    new String(getResourceBytes(clazz, resource), UTF_8)

  def getResourceBytes(clazz: Class[_], resource: String): Array[Byte] = {
    val input = requireNonNull(clazz.getResourceAsStream(resource), resource)
    try input.readAllBytes()
    finally input.close()
  }
}
