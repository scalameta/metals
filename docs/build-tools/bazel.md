---
id: bazel
title: Bazel
---

Bazel is a build tool that is used by Google and other companies to build large
monorepos. It is a bit more complex to set up than other build tools, but it is
praised for its speed and scalability. It is also being used more and more in a
lot of Scala codebases.

Since, there is no way to currently export Bazel build definition to Bloop,
Metals is able to work with Bazel via the
[Bazel BSP server](https://github.com/JetBrains/bazel-bsp). Thanks to the great
work by the JetBrains team, Metals is able to work almost directly with Bazel
and reuse all its perks.

## Automatic installation

The first time you open Metals in a new Bazel workspace you will be prompted to
import the build. Select "Import Build" to start the automatic installation.
This will create all the needed files, this includes:

- `projectview.bazel`

This file contains the default settings configured so that Metals can work with
Bazel without a hitch. By default it will work for all targets in the workspace,
but can be easily modified to only work with a subset, which will work much
faster.

```
targets:
    //...

build_manual_targets: false

derive_targets_from_directories: false

enabled_rules:
    io_bazel_rules_scala
    rules_java
    rules_jvm

```

`enabled_rules` option is especially important, as there are cases that Bazel
BSP will not correctly detect scala rules within the workspace. You can share
this file across the team or add it to your .gitignore.

- `.bsp/bazelbsp.json`

This file contains all the neccessary info to start the Bazel BSP server.

```
{
  "name": "bazelbsp",
  "argv": [
    "/usr/lib/jvm/graal17/bin/java",
    "-classpath",
    "<classpath on your machine>",
    "org.jetbrains.bsp.bazel.server.ServerInitializer",
    "/path/to/workspace",
    "/path/to/workspace/projectview.bazelproject",
    "false"
  ],
  "version": "3.1.0-20240130-33760f0-NIGHTLY",
  "bspVersion": "2.1.0",
  "languages": [
    "scala",
    "java",
    "kotlin"
  ]
}
```

After these files are created and Metals connected to the build server, you
should then be able to edit and compile your code utilizing most of the
available features.

There are some caveats however, as it's not currently possible to set up
everything automatically for the user:

1. Sources need to be enabled via `fetch_sources` for library dependencies in
   the Bazel definition. For example:

```
maven_install(
    artifacts =  ["com.github.ghostdogpr:caliban_2.13:2.0.1"],
    maven_install_json = "@//:maven_install.json",
    repositories = [
        "https://maven.google.com",
        "https://repo1.maven.org/maven2",
    ],
    version_conflict_policy = "pinned",
    fetch_sources = True,
)
```

2. Go to references requires semanticdb plugin to be enabled in the Bazel build.
   This can be done by adding the following to your `BUILD` file:

```
load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")

scala_toolchain(
    name = "semanticdb_toolchain_impl",
    enable_semanticdb = True,
    semanticdb_bundle_in_jar = False,
    visibility = ["//visibility:public"],
)

toolchain(
    name = "semanticdb_toolchain",
    toolchain = "semanticdb_toolchain_impl",
    toolchain_type = "@io_bazel_rules_scala//scala:toolchain_type",
    visibility = ["//visibility:public"],
)
```

and in your `WORKSPACE` file:

```Scala
register_toolchains(
    "//:semanticdb_toolchain",
)
```

You can find a full example in
[the official rules scala repository](https://github.com/bazelbuild/rules_scala/tree/master/examples/semanticdb)

## Manual installation

Manual installation is not recommended for Bazel, as there is a couple of steps,
which are not trivial for beginners. But if you are interested in the process
anyway, here is a short guide:

1. Have [coursier](https://get-coursier.io/docs/cli-installation) installed
2. Run in the directory where Bazel BSP should be installed:

```shell
cs launch org.jetbrains.bsp:bazel-bsp:<version> -M org.jetbrains.bsp.bazel.install.Install -- --targets //...
```

Please check [release](https://github.com/JetBrains/bazel-bsp/releases) to find
the newest available version or check for the latest nightly using
`cs complete-dep org.jetbrains.bsp:bazel-bsp:` command.

3. Use `Metals: Connect to build server` command if Metals didn't pick up the
   new bsp configuration automatically.

## Mezel

If you are unable to make the default Bazel BSP server work, you can try using
[Mezel](https://github.com/ValdemarGr/mezel), which is an alternative
implementation of BSP for Bazel.

Check out the
[Mezel README](https://github.com/ValdemarGr/mezel/blob/master/README.md) for
more information.

## Troubleshooting

### Bazel is restarting between CLI and Metals

This possibly means that some of the environment variables are set to different
values between what Metals and Bazel are using. Some of the variables that
influence this are PATH, PWD, JAVA_HOME and others. Make sure that these are set
to the same values in both environments. If possible let us know if we can
improve the default behaviour of Metals to avoid this issue.

Alternatively, adding `--incompatible_strict_action_env=true` option to .bazelrc
might help in your case.
