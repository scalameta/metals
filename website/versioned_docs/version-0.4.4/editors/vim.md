---
id: version-0.4.4-vim
title: Vim
original_id: vim
---

Metals works in vim thanks to the the
[`vim-lsc`](https://github.com/natebosch/vim-lsc/) extension.

![Vim demo](https://i.imgur.com/jMMEmCC.gif)


## Requirements

**Java 8**. Metals does not work with Java 11 yet so make sure the JAVA_HOME
environment variable points to Java 8.

**macOS, Linux or Windows**. Metals is developed on macOS and every PR is
tested on Ubuntu+Windows.

**Scala 2.12 and 2.11**. Metals works only with Scala versions 2.12.8, 2.12.7, 2.12.6, 2.12.5, 2.12.4, 2.11.12, 2.11.11, 2.11.10 and 2.11.9.
Note that 2.10.x and 2.13.0-M5 are not supported.

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
  \  'scala': {
  \    'command': 'metals-vim',
  \    'log_level': 'Log'
  \  }
  \}
let g:lsc_auto_map = {
  \  'GoToDefinition': 'gd',
  \}
```

Run `:PlugInstall` to install the plugin. If you already have `vim-lsc`
installed, be sure to update to the latest version with `:PlugUpdate`.

If you start Vim now then it will fail since the `metals-vim` binary does not
exist yet.


Next, build a `metals-vim` binary for the latest Metals release using the
[Coursier](https://github.com/coursier/coursier) command-line interface.

<table>
<thead>
<th>Version</th>
<th>Published</th>
<th>Resolver</th>
</thead>
<tbody>
<tr>
<td>0.4.4</td>
<td>02 Feb 2019 18:09</td>
<td><code>-r sonatype:releases</code></td>
</tr>
<tr>
<td>0.4.4+15-ac8fa735-SNAPSHOT</td>
<td>14 Feb 2019 16:56</td>
<td><code>-r sonatype:snapshots</code></td>
</tr>
</tbody>
</table>

```sh
# Make sure to use coursier v1.1.0-M9 or newer.
curl -L -o coursier https://git.io/coursier
chmod +x coursier
./coursier bootstrap \
  --java-opt -XX:+UseG1GC \
  --java-opt -XX:+UseStringDeduplication  \
  --java-opt -Xss4m \
  --java-opt -Xms100m \
  --java-opt -Dmetals.client=vim-lsc \
  org.scalameta:metals_2.12:0.4.4 \
  -r bintray:scalacenter/releases \
  -r sonatype:snapshots \
  -o /usr/local/bin/metals-vim -f
```

Make sure the generated `metals-vim` binary is available on your `$PATH`.

The `-Dmetals.client=vim-lsc` flag is important since it configures Metals for
usage with the `vim-lsc` client.


## Importing a build

The first time you open Metals in a new workspace it prompts you to import the build.
Click "Import build" to start the installation step.

![Import build](https://i.imgur.com/EAyt0xP.png)

- "Not now" disables this prompt for 2 minutes.
- "Don't show again" disables this prompt forever, use `rm -rf .metals/` to re-enable
  the prompt.
- Behind the scenes, Metals uses [Bloop](https://scalacenter.github.io/bloop/) to
  import sbt builds, but you don't need Bloop installed on your machine to run this step.

Once the import step completes, compilation starts for your open `*.scala`
files.

Once the sources have compiled successfully, you can navigate the codebase with
goto definition.

### Custom sbt launcher

By default, Metals runs an embedded `sbt-launch.jar` launcher that respects `.sbtopts` and `.jvmopts`.
However, the environment variables `SBT_OPTS` and `JAVA_OPTS` are not respected.


Update the server property `-Dmetals.sbt-script=/path/to/sbt` to use a custom
`sbt` script instead of the default Metals launcher if you need further
customizations like reading environment variables.


### Speeding up import

The "Import build" step can take a long time, especially the first time you
run it in a new build. The exact time depends on the complexity of the build and
if library dependencies need to be downloaded. For example, this step can take
everything from 10 seconds in small cached builds up to 10-15 minutes in large
uncached builds.

Consult the [Bloop documentation](https://scalacenter.github.io/bloop/docs/build-tools/sbt#speeding-up-build-export)
to learn how to speed up build import.

### Importing changes

When you change `build.sbt` or sources under `project/`, you will be prompted to
re-import the build.

![Import sbt changes](https://i.imgur.com/hxOKfr0.png)


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

![Run Doctor](https://i.imgur.com/yelm0jd.png)

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

![Document Symbols](https://i.imgur.com/T8SUD7B.png)

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


## Gitignore `.metals/` and `.bloop/`

The Metals server places logs and other files in the `.metals/` directory. The
Bloop compile server places logs and compilation artifacts in the `.bloop`
directory. It's recommended to ignore these directories from version control
systems like git.

```sh
# ~/.gitignore
.metals/
.bloop/
```

