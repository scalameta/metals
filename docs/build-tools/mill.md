---
id: mill
title: Mill
---

Mill is a build tool developed by Li Haoyi in order to create something simpler
and more intuitive than most of the other build tools today.  There is extensive
documentation on the [Mill website](https://com-lihaoyi.github.io/mill/).

```scala mdoc:automatic-installation:Mill
```

To force a Mill version you can write it to a file named `.mill-version`
in the workspace directory.

## Manual installation

Manual installation is not recommended, but it's pretty easy to do. There are
only two steps involved.

First add one import line to your `build.sc` file or in any other file it
depends on:

`` import $ivy.`com.lihaoyi::mill-contrib-bloop:VERSION` ``

Remember to replace the `VERSION` with your Mill version.

After adding the line you should be able to generate the Bloop config files needed
to work with Metals using the below command:

`mill mill.contrib.Bloop/install`

Afterwards, you can just open Metals and start working on your code.
