---
id: vim
sidebar_label: Vim
title: Vim
---

![nvim-metals demo](https://i.imgur.com/BglIFli.gif)

While Metals works with most LSP clients for [Vim](https://www.vim.org/) and
[Neovim](https://neovim.io/), we recommend using the dedicated Neovim plugin to
get the best Metals support. Metals has many specific commands and LSP
extensions that won't be available when not using the extension.

```scala mdoc:requirements

```

## nvim-metals

[`nvim-metals`](https://github.com/scalameta/nvim-metals) Is the the dedicated
Metals extension for the [built-in LSP
support](https://neovim.io/doc/user/lsp.html) in Neovim.

To get started with installation please see the `nvim-metals`
[prerequisites](https://github.com/scalameta/nvim-metals#prerequisites) and
[installation steps](https://github.com/scalameta/nvim-metals#installation).

```scala mdoc:releases

```

Keep in mind that by default Neovim doesn't have default mappings for the
functionality you'll want like, hovers, goto definition, method signatures, etc.
Youc can find a full example configuration of these in the [example
configuration](https://github.com/scalameta/nvim-metals/discussions/39).

For a guide on all the available features in `nvim-metals`, refer to the
[features list](https://github.com/scalameta/nvim-metals/discussions/279).

## Vim alternatives

There are multiple Vim alternatives if you're not a Neovim user. Metals did have
a Metals-specific plugin that worked with Vim,
[`coc-metals`](https://github.com/scalameta/coc-metals), but it doesn't work
with the newest versions of Metals and is currently [deprecated and
unmaintained](https://github.com/scalameta/coc-metals/issues/460).

### Using an alternative LSP Client

There are multiple other LSP clients that work with Vim. Here are a few:

- [`natebosch/vim-lsc`](https://github.com/natebosch/vim-lsc/): simple installation and written in Vimscript.
- [`vim-lsp`](https://github.com/prabirshrestha/vim-lsp): simple installation and written in
    Vimscript.
- [`yegappan/lsp`](https://github.com/yegappan/lsp): Very new and targeting
    Vim9.

Keep in mind that they have varying levels of LSP support, don't support Metals
specific commands (like build import), or Metals specific LSP extensions (like
tree view), and you need to manage the Metals installation yourself.

There are two ways to install Metals yourself in order to work with an
alternative client.

1. Most easily is to just use [Coursier](https://get-coursier.io/) to do a `cs
   install metals`.
2. Generating a Metals binary yourself.

```scala mdoc:generic

```

```scala mdoc:bootstrap:metals-vim vim-lsc

```

The `-Dmetals.client=vim-lsc` flag is there just as a helper to match your
potential client. So make sure it matches your client name. This line isn't
mandatory though as your client should be able to fully be configured via
`InitializationOptions`. You can read more about his
[here](https://scalameta.org/metals/blog/2020/07/23/configuring-a-client#initializationoptions).

## Getting help

There is an active community using Vim and Metals. Apart from [creating an
issue](https://github.com/scalameta/nvim-metals/issues/new/choose) or [starting
a discussion](https://github.com/scalameta/nvim-metals/discussions) for
`nvim-metals` users, you can also ask questions in our `#vim-users` [Discord
Channel](https://discord.gg/FaVDrJegEh) or [Matrix
Bridge](https://matrix.to/#/#scalameta:vim-users).
