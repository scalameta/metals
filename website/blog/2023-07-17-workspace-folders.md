---
authors: kmarek
title: Workspace folders
---

In the upcoming version of metals we will add support for
[LSP workspace folders](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workspace_workspaceFolders).
This feature allows you to load multiple Scala projects/modules into the same
workspace without the need to switch between multiple windows.

## The new multi-root approach

Before this feature metals would only support a single project treating a
workspace root folder as the root of the project. The workspace root was
established based on the `rootUri` field of
[`InitializeParams`](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#initializeParams)
sent by the client upon initialization.

Now a single metals instance can accommodate several projects (or multiple roots
of a project) at the time. In `InitializeParams` metals first looks for
projects' roots under `workspaceFolders` in `InitializeParams` and if empty we
still fallback to the `rootUri`. Loaded projects can be changed dynamically
though `didChangeWorkspaceFolders` notifications, which allow the client
(editor) to inform metals about any added or removed projects.

All workspace folders are handled in metals separately and are oblivious of each
other. E.g. for the following multi project structure

```
project1
|- build.sbt
|- src
  |- Main.scala

project2
|- build.sbt
|- src
  |- Main.scala
```

we will keep two separate entities: one responsible for `project1`, and another
one for `project2`. Upon receiving most requests metals will redirect them to
the entity responsible for the project of interest. If there are no other clues
the project is chosen based on the currently opened file. E.g. if the user wants
to insert an inferred type in the file `../project1/src/Main`, the request
received by Metals will be redirected to the entity responsible for `project1`.

For some requests we collect information from all the projects and send a joint
result, e.g. when searching for workspace symbols.

## How do I use the multi-root feature?

### VSCode

In VSCode workspace folder support is achieved by
[multi-root workspaces](https://code.visualstudio.com/docs/editor/multi-root-workspaces).
To load multiple projects into a single workspace you can simply open one of the
projects and add the other one using `File > Add Folder to Workspace` and then
choosing the correct folder.

![add-workspace-folder](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-07-17-workspace-folders/LTYrx9V.gif?raw=true)

Now you have two projects loaded side by side, so you can easily see both and
switch between them. All of the current metals functionality accommodates
multiple projects, so you can use metals the same way as you did before. The
biggest changes will be visible in the places where information from the whole
workspace is collected, like workspace symbol search, test explorer, or metals
doctor.

![multi-root-tests](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-07-17-workspace-folders/zWmmsC2.gif?raw=true)

The target project for a command is usually chosen based on the currently opened
file. E.g. if you run `Switch build server` the command it will be executed for
the project in focus. If no project is in focus the editor will explicitly ask
for which project the command should be executed.

![target-folder](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-07-17-workspace-folders/tV7K822.gif?raw=true)

Finally, logs can still be found in the `.metals/metals.log` in the root of each
project. Note, that for the time being all information is logged to all opened
workspace folders, so anything logged for `project1` will also be visible in
logs for `project2`.

### nvim-metals (_section written by [ckipp01](https://github.com/ckipp01)_)

When using nvim-metals, you'll start just like you do with any other project.
Since the idea of a workspace is a bit "artificial" with Neovim, you can really
just add any new root to have a multi-root workspace. All you'll need to do is
navigate to a file at the root level of the workspace you'd like to add, and use
the
[`vim.lsp.buf.add_workspace_folder()`](https://neovim.io/doc/user/lsp.html#vim.lsp.buf.add_workspace_folder())
function to add the folder containing the file you're in as another root.

![add_workspace_folder](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-07-17-workspace-folders/E8iriR9.gif?raw=true)

To make this easier, you can also just create a mapping to use.

```lua
vim.keymap.set("n", "<leader>awf", vim.lsp.buf.add_workspace_folder)
```

To verify that this has worked correctly you should be able to now see both
workspaces reflected in your Metals Doctor.

![nvim-metals doctor](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-07-17-workspace-folders/2u48wDK.gif?raw=true)

You should also see that some commands, like the
[`vim.lsp.buf.workspace_symbol()`](https://neovim.io/doc/user/lsp.html#vim.lsp.buf.workspace_symbol())
now show results from all the added workspaces.

![workspace_symbols](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-07-17-workspace-folders/RczJIcp.gif?raw=true)

## Changes for the clients

Since workspace folders are a part of the LSP for any client implementing this
capability the multi-root support should work out of the box, however, there
will be a few minor changes to needed accommodate the new approach.

1. The metals doctor result json format will change to contain a list of
   diagnostics for each workspace folder. Current format can be found in the
   description of `RunDoctor` command (visible in `ClientCommands.scala` in
   `metals` repo).
2. For test explorer users `BuildTargetUpdate` will also now contain information
   about the target folder.

## Quick summary

Metals now supports the
[LSP workspace folders](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workspace_workspaceFolders),
which in VSCode are implemented by
[multi-root workspaces](https://code.visualstudio.com/docs/editor/multi-root-workspaces).

If you haven't yet make sure to try out our new multi-root support!
