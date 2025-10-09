---
id: online-ides
title: Online IDEs
---

Metals can also be installed in some online environments, which enable users to
work on their code inside of a browser. This can be a good option when users need
to set up their workspace quickly or are lacking appropriate resources on their
local machine.

## Example repository for Metals

An example repository, which can be used to optimize the setup of your own online environment, 
can be found [here](https://github.com/scalameta/metals-gitpod-sample). The project
contains, out of the box, everything that you need to start experimenting with
Metals in an online environment similar to Visual Studio Code. 

## Open VSX

Metals extension is available in the [Open VSX Registry](https://open-vsx.org/)
which is used to provide extensions in Gitpod.
You can find the newest version of Metals in there.

# GitHub Codespaces and GitHub.dev

First, some definitions:

- [GitHub Codespaces](https://github.com/features/codespaces) are remote dev containers you can connect to using VS Code Desktop or VS Code Web.
- GitHub.dev, also referred to as ["Browser-based editor"](https://code.visualstudio.com/docs/remote/codespaces#_browserbased-editor), is a version of VS Code Web that runs entirely in your browser (crucially, this is different than connecting to a Codespace using VS Code Web).

Metals fully works in GitHub Codespaces, but not on GitHub.dev.

This is because Codespaces can run Java (being full fledged containerized environments) and extensions have access to a real file system, whereas GitHub.dev is limited to browser-only technologies, hence it can't run Java and it accesses files using a virtual file system (see [Virtual Workspaces](https://github.com/microsoft/vscode/wiki/Virtual-Workspaces)). These limitations make it impossible to run Metals on GitHub.dev. For more details, see [this issue](https://github.com/scalameta/metals-feature-requests/issues/225).
