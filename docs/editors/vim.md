---
id: vim
title: Vim
---

Metals works in vim thanks to the the
[`vim-lsc`](https://github.com/natebosch/vim-lsc/) extension.

![Vim demo](../assets/vim-demo.gif)

```scala mdoc:requirements

```

## Installing the plugin

First, install the following plugins

- [`natebosch/vim-lsc`](https://github.com/natebosch/vim-lsc/): Language Server
  Protocol client to communicate with the Metals language server.
- [`derekwyatt/vim-scala`](https://github.com/derekwyatt/vim-scala): for syntax
  highlighting Scala and sbt source files.

Assuming [`vim-plug`](https://github.com/junegunn/vim-plug) is used (another
plugin manager like vundle works too), update `~/.vimrc` to include the
following settings.

```vim
" ~/.vimrc

" Configuration for vim-plug
Plug 'derekwyatt/vim-scala'
Plug 'natebosch/vim-lsc'

" Configuration for vim-scala
au BufRead,BufNewFile *.sbt set filetype=scala

" Configuration for vim-lsc
let g:lsc_enable_autocomplete = v:false
let g:lsc_server_commands = {
  \ 'scala': 'metals-vim'
  \}
let g:lsc_auto_map = {
    \ 'GoToDefinition': 'gd',
    \}
```

Run `:PlugInstall` to install the plugin. If you already have `vim-lsc`
installed, be sure to update to the latest version with `:PlugUpdate`.

If you start Vim now then it will fail since the `metals-vim` binary does not
exist yet.

```scala mdoc:bootstrap:metals-vim vim-lsc

```

The `-Dmetals.client=vim-lsc` flag is important since it configures Metals for
usage with the `vim-lsc` client.

```scala mdoc:editor:vim

```

## Learn more about vim-lsc

For comprehensive documentation about vim-lsc, run the following command.

```vim
:help lsc
```

## Customize goto definition

Configure `~/.vimrc` to use a different command than `gd` for triggering "goto
definition".

```vim
" ~/.vimrc
let g:lsc_auto_map = {
    \ 'GoToDefinition': 'gd',
    \}
```

## List all workspace compile errors

To list all compilation errors and warnings in the workspace, run the following
command.

```vim
:LSClientAllDiagnostics
```

This is helpful to see compilation errors in different files from your current
open buffer.

## Close buffer without exiting

To close a buffer and return to the previous buffer, run the following command.

```vim
:bd
```

This command is helpful when navigating in library dependency sources in the
`.metals/readonly` directory.

## Shut down the language server

The Metals server is shutdown when you exit vim as usual.

```vim
:wq
```

This step clean ups resources that are used by the server.

## Run doctor

To troubleshoot problems with your build workspace execute the following
command.

```vim
:call lsc#server#call(&filetype, 'workspace/executeCommand', { 'command': 'doctor-run' }, function('abs'))
```

This command opens your browser with a table like this.

![Run Doctor](assets/http-run-doctor.png)

The callback `function('abs')` can be replaced with any function that does
nothing.

## Manually start build import

To manually start the `sbt bloopInstall` step, call the following command below.
This command works only for sbt builds at the moment.

```vim
:call lsc#server#call(&filetype, 'workspace/executeCommand', { 'command': 'build-import' }, function('abs'))
```

The callback `function('abs')` can be replaced with any function that does
nothing.

## Manually connect with build server

To manually tell Metals to establish a connection with the build server, call
the command below. This command works only at the moment if there is a `.bloop/`
directory containing JSON files.

```vim
:call lsc#server#call(&filetype, 'workspace/executeCommand', { 'command': 'build-connect' }, function('abs'))
```

The callback `function('abs')` can be replaced with any function that does
nothing.

## Show document symbols

Run `:LSClientDocumentSymbol` to show a symbol outline for the current file.

![Document Symbols](assets/vim-document-symbols.png)

## Known issues

- [vim-lsc#123](https://github.com/natebosch/vim-lsc/issues/123): newlines in
  compile errors are escaped with `^@`.

## LanguageClient-neovim

The `vim-lsc` client is recommended over the
[`autozimu/LanguageClient-neovim`](https://github.com/autozimu/LanguageClient-neovim/)
client for the following reasons:

- Installation is more complicated compared to vim-lsc
- `LanguageClient-neovim` does not implement `window/showMessageRequest`
- `LanguageClient-neovim` does not implement `window/logMessage`

```scala mdoc:generic

```
