---
id: mill
title: Mill
---

Mill is a build tool initially developed by Li Haoyi in order to create something simpler
and more intuitive than most of the other build tools today.  There is extensive
documentation on the [Mill website](https://com-lihaoyi.github.io/mill/).

```scala mdoc:automatic-installation:Mill
```

To force a Mill version you can write it to a file named `.mill-version`
in the workspace directory.

## Manual installation

Manual installation is not recommended by Metals, but it's pretty easy to do. 
You can choose between two server implementations.

### Bloop

Using Mill with Bloop is the current preferred way by Metals.

Metals requires the Bloop config files, which you can generate with the following command:

``mill --import "ivy:com.lihaoyi::mill-contrib-bloop:" mill.contrib.bloop.Bloop/install``

Afterwards, you can just open Metals and start working on your code.

### Mill BSP

Mill also provides a built-in BSP server. To generate the BSP connection discovery files, run the following command:

``mill mill.bsp.BSP/install``
