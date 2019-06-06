---
id: mill
title: Mill
---

Mill is one of the newest build tools developed by Li Haoyi in order to create
something simpler and more intuitive than most of other today's build tools.
There is an extensive documentation on the
[Mill website](http://www.lihaoyi.com/mill/).

## Automatic installation

The first time you open Metals in a new workspace it prompts you to import the
build. Select "Import build" to start automatic installation. After it's
finished you should be able edit and compile your code.

## Manual installation

Manual instalation is not recommended, but it's pretty easy to do. There are
only two steps involved.

First add one import line to your `build.sc` file or in any other file it
depends on:

`` import $ivy.`com.lihaoyi::mill-contrib-bloop:VERSION` ``

Remember to replace the `VERSION` with your mill version.

After adding the line you should be able to generate Bloop config files needed
to work with Metals using the below command:

`mill mill.contrib.Bloop/install`

Afterwards just can just open Metals and start working on your code.
