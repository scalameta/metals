package scala.meta.internal.metals.config.option

import scala.meta.internal.metals.config.UserConfiguration

abstract class ConfigurationOptionWithLens[A, C](
    fromConfig: C => A,
    updateConfig: (C, A) => C,
) extends ConfigurationOption[A, C] {
  final def get(config: C): A = fromConfig(config)
  final def update(
      config: C,
      value: A,
  ): C =
    updateConfig(config, value)
}

abstract class LensConfigurationOption[A](
    fromConfig: UserConfiguration => A,
    updateConfig: (UserConfiguration, A) => UserConfiguration,
) extends ConfigurationOptionWithLens[A, UserConfiguration](
      fromConfig,
      updateConfig,
    )
    with UserConfigurationOption[A]
