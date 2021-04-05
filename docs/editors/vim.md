---
id: vim
title: Vim
---

![Vim demo](https://i.imgur.com/4BYHCCL.gif)

Metals works with most LSP clients for Vim, but we recommend using one of the
Metals-specific extensions to get the best experience since they offer
Metals-specific commands and implement the Metals LSP extensions. 

- [coc-metals](https://github.com/scalameta/coc-metals) An extension for
    [`coc.nvim`](https://github.com/neoclide/coc.nvim) This provides the most
    feature-filled experience. This is recommended for most users, and the
    majority of the documentation below reflects this option.
- [nvim-metals](https://github.com/scalameta/nvim-metals) A Lua extension for
    the [built-in LSP support](https://neovim.io/doc/user/lsp.html) in Neovim
    0.5.x. Recommended for those that like a thinner client. _NOTE_: This option
    is _not_ as stable as `coc-metals` yet and requires the nightly version of
    Neovim. You can find detailed instructions for this plugin
    [here](https://github.com/scalameta/nvim-metals/blob/master/doc/metals.txt).

```scala mdoc:requirements

```

## Installing Vim

The coc.nvim plugin requires either **Vim >= 8.1** or **Neovim >= 0.3.1**. Make
sure you have the correct version installed. While it works with both Vim and
Neovim, we recommend using Neovim since it provides a smoother experience with
some of the features.

```sh
# If using Vim
vim --version | head
VIM - Vi IMproved 8.2

# If using Neovim
nvim --version | head
NVIM v0.4.3
```
## Installing Node

`coc.nvim` requires [Node.js](https://nodejs.org/en/download/) in order to work.
It also uses [Yarn](https://yarnpkg.com/en/docs/install#debian-stable) to manage
extensions but you could opt-out of it and use `vim-plug` instead.

For convenience we recommend installing both via your favorite package manager
or manually:

```sh
curl -sL install-node.now.sh/lts | sh
curl --compressed -o- -L https://yarnpkg.com/install.sh | bash
```

## Installing coc.nvim

Once the requirements are satisfied, we can now proceed to install
[`neoclide/coc.nvim`] (https://github.com/neoclide/coc.nvim/), which provides
Language Server Protocol support to Vim/Nvim  to communicate with the Metals
language server.

Assuming [`vim-plug`](https://github.com/junegunn/vim-plug) is used (another
plugin manager like vundle works too), update your `~/.vimrc` to include the
following settings.

```vim
Plug 'neoclide/coc.nvim', {'branch': 'release'}
```

Run `:PlugInstall` to install the plugin. If you already have `coc.nvim`
installed, be sure to update to the latest version with `:PlugUpdate`.

`coc.nvim` uses [jsonc](https://code.visualstudio.com/docs/languages/json) as a
configuration file format. It's basically json with comment support.

In order to get comment highlighting, please add:

```vim
autocmd FileType json syntax match Comment +\/\/.\+$+
```
### Recommended coc.nvim mappings

`coc.nvim` doesn't come with a default key mapping for LSP commands, so you need
to configure it yourself.

Here's a recommended configuration:

```vim
" ~/.vimrc
" Configuration for coc.nvim

" If hidden is not set, TextEdit might fail.
set hidden

" Some servers have issues with backup files
set nobackup
set nowritebackup

" You will have a bad experience with diagnostic messages with the default 4000.
set updatetime=300

" Don't give |ins-completion-menu| messages.
set shortmess+=c

" Always show signcolumns
set signcolumn=yes

" Help Vim recognize *.sbt and *.sc as Scala files
au BufRead,BufNewFile *.sbt,*.sc set filetype=scala

" Use tab for trigger completion with characters ahead and navigate.
" Use command ':verbose imap <tab>' to make sure tab is not mapped by other plugin.
inoremap <silent><expr> <TAB>
      \ pumvisible() ? "\<C-n>" :
      \ <SID>check_back_space() ? "\<TAB>" :
      \ coc#refresh()
inoremap <expr><S-TAB> pumvisible() ? "\<C-p>" : "\<C-h>"

" Used in the tab autocompletion for coc
function! s:check_back_space() abort
  let col = col('.') - 1
  return !col || getline('.')[col - 1]  =~# '\s'
endfunction

" Use <c-space> to trigger completion.
if has('nvim')
  inoremap <silent><expr> <c-space> coc#refresh()
else
  inoremap <silent><expr> <c-@> coc#refresh()
endif

" Make <CR> auto-select the first completion item and notify coc.nvim to
" format on enter, <cr> could be remapped by other vim plugin
inoremap <silent><expr> <cr> pumvisible() ? coc#_select_confirm()
                              \: "\<C-g>u\<CR>\<c-r>=coc#on_enter()\<CR>"

" Use `[g` and `]g` to navigate diagnostics
nmap <silent> [g <Plug>(coc-diagnostic-prev)
nmap <silent> ]g <Plug>(coc-diagnostic-next)

" Remap keys for gotos
nmap <silent> gd <Plug>(coc-definition)
nmap <silent> gy <Plug>(coc-type-definition)
nmap <silent> gi <Plug>(coc-implementation)
nmap <silent> gr <Plug>(coc-references)

" Used to expand decorations in worksheets
nmap <Leader>ws <Plug>(coc-metals-expand-decoration)

" Use K to either doHover or show documentation in preview window
nnoremap <silent> K :call <SID>show_documentation()<CR>

function! s:show_documentation()
  if (index(['vim','help'], &filetype) >= 0)
    execute 'h '.expand('<cword>')
  elseif (coc#rpc#ready())
    call CocActionAsync('doHover')
  else
    execute '!' . &keywordprg . " " . expand('<cword>')
  endif
endfunction

" Highlight symbol under cursor on CursorHold
autocmd CursorHold * silent call CocActionAsync('highlight')

" Remap for rename current word
nmap <leader>rn <Plug>(coc-rename)

augroup mygroup
  autocmd!
  " Update signature help on jump placeholder
  autocmd User CocJumpPlaceholder call CocActionAsync('showSignatureHelp')
augroup end

" Applying codeAction to the selected region.
" Example: `<leader>aap` for current paragraph
xmap <leader>a  <Plug>(coc-codeaction-selected)
nmap <leader>a  <Plug>(coc-codeaction-selected)

" Remap keys for applying codeAction to the current buffer.
nmap <leader>ac  <Plug>(coc-codeaction)
" Apply AutoFix to problem on the current line.
nmap <leader>qf  <Plug>(coc-fix-current)

" Use `:Format` to format current buffer
command! -nargs=0 Format :call CocAction('format')

" Use `:Fold` to fold current buffer
command! -nargs=? Fold :call     CocAction('fold', <f-args>)

" Trigger for code actions
" Make sure `"codeLens.enable": true` is set in your coc config
nnoremap <leader>cl :<C-u>call CocActionAsync('codeLensAction')<CR>

" Mappings for CoCList
" Show all diagnostics.
nnoremap <silent><nowait> <space>a  :<C-u>CocList diagnostics<cr>
" Manage extensions.
nnoremap <silent><nowait> <space>e  :<C-u>CocList extensions<cr>
" Show commands.
nnoremap <silent><nowait> <space>c  :<C-u>CocList commands<cr>
" Find symbol of current document.
nnoremap <silent><nowait> <space>o  :<C-u>CocList outline<cr>
" Search workspace symbols.
nnoremap <silent><nowait> <space>s  :<C-u>CocList -I symbols<cr>
" Do default action for next item.
nnoremap <silent><nowait> <space>j  :<C-u>CocNext<CR>
" Do default action for previous item.
nnoremap <silent><nowait> <space>k  :<C-u>CocPrev<CR>
" Resume latest coc list.
nnoremap <silent><nowait> <space>p  :<C-u>CocListResume<CR>

" Notify coc.nvim that <enter> has been pressed.
" Currently used for the formatOnType feature.
inoremap <silent><expr> <cr> pumvisible() ? coc#_select_confirm()
      \: "\<C-g>u\<CR>\<c-r>=coc#on_enter()\<CR>"

" Toggle panel with Tree Views
nnoremap <silent> <space>t :<C-u>CocCommand metals.tvp<CR>
" Toggle Tree View 'metalsPackages'
nnoremap <silent> <space>tp :<C-u>CocCommand metals.tvp metalsPackages<CR>
" Toggle Tree View 'metalsCompile'
nnoremap <silent> <space>tc :<C-u>CocCommand metals.tvp metalsCompile<CR>
" Toggle Tree View 'metalsBuild'
nnoremap <silent> <space>tb :<C-u>CocCommand metals.tvp metalsBuild<CR>
" Reveal current current class (trait or object) in Tree View 'metalsPackages'
nnoremap <silent> <space>tf :<C-u>CocCommand metals.revealInTreeView metalsPackages<CR>
```

### Installing coc-metals

Once you have `coc.nvim` installed, you can then install Metals a few different
ways. The easiest is by running:

```vim
:CocInstall coc-metals
```

If you are using the latest stable release of coc.nvim, then this will
automatically check for updates each day. However, if you're on the master
branch of coc.nvim, this will no longer happen by default. You can read more
about this [here](https://github.com/neoclide/coc.nvim/issues/1937).

If you'd like to use the latest changes on master, you can also just build from
source by using your vim plugin manager. Here is an example using `vim-plug`:

```vim
Plug 'scalameta/coc-metals', {'do': 'yarn install --frozen-lockfile'}
```

Then, issue a `:PlugInstall` to install the extension, and regularly a
`:PlugUpdate` to update it and pull in the latest changes.

**NOTE*** Make sure you don't have the extension installed both ways. So if you
have installed it with the built-in extension management of coc.nvim first and
are not switching, make sure to uninstall the old version first.

```vim
:CocUninstall coc-metals
```

```scala mdoc:editor:vim
Update the `metals.sbtScript` setting to use a custom `sbt` script instead of the
default Metals launcher if you need further customizations like reading environment
variables.

![Sbt Launcher](https://i.imgur.com/meciPTg.png)
```

## Configure Java version
The `coc-metals` extension uses by default the `JAVA_HOME` environment variable
(via [`find-java-home`](https://www.npmjs.com/package/find-java-home)) to locate
the `java` executable.

![No Java Home](https://i.imgur.com/clDfPMk.png)

If no `JAVA_HOME` is detected you can then Open Settings by following the
instructions or do it at a later time by using `:CocConfig` or `:CocConfigLocal`
which will open up your configuration where you can manually enter your
JAVA_HOME location.

![Enter Java Home](https://i.imgur.com/wK07Vju.png)

## Using latest Metals SNAPSHOT

Update the "Server Version" setting to try out the latest pending Metals
features.

```scala mdoc:releases

```

After updating the version, you'll be triggered to reload the window.
This will be necessary before the new version will be downloaded and used.

![Update Metals Version](https://i.imgur.com/VUCdQvi.png)

## List all workspace compile errors

To list all compilation errors and warnings in the workspace, run the following
command.

```vim
:CocList diagnostics
```

Or use the default recommended mapping `<space> a`.

This is helpful to see compilation errors in different files from your current
open buffer.

![Diagnostics](https://i.imgur.com/cer22HW.png)

```scala mdoc:worksheet:vim
```
![Decorations with worksheets](https://i.imgur.com/Bt6DMtH.png)

## Tree View Protocol

![Tree View Protocol](https://i.imgur.com/GvcU9Mu.gif)

coc-metals has a built-in implementation of the [Tree View
Protocol](https://scalameta.org/metals/docs/integrations/tree-view-protocol.html).
If you have the [recommended mappings](vim.md#recommended-cocnvim-mappings) copied, you'll notice
that in the bottom you'll have some TVP related settings. You can start by
opening the TVP panel by using the default `<space> t`. Once open, you'll see
there are three parts to the panel. The first being the `MetalsCompile` window
where you can see ongoing compilations, the second is the `MetalsPackages`
window where you are able to see a tree view of all your packages, and finally
the `metalsBuild` window where you have build related commands.

You are able to trigger the commands while being on top of the option you are
attempting to trigger and pressing `r`. You can change this default in the
settings. You can find all the relevant TVP settings below in the [Available
Configuration Options](#tree-view-protocol-configuration-options).

### Tree View Protocol configuration options

   Configuration Option                         |      Description
----------------------------                    |---------------------------
`metals.treeviews.toggleNode`                   | Expand / Collapse tree node (default `<CR>`)
`metals.treeviews.initialWidth`                 | Initial Tree Views panels (default `40`)
`metals.treeviews.initialViews`                 | Initial views that the Tree View Panel displays. Don't mess with this unless you know what you're doing.
`metals.treeviews.gotoLastChild`                | Go to the last child Node (defalt `J`)
`metals.treeviews.gotoParentNode`               | Go to parent Node (default `p`)
`metals.treeviews.gotoFirstChild`               | Go to first child Node (default `K`)
`metals.treeviews.executeCommand`               | Execute command for node (default `r`)
`metals.treeviews.gotoPrevSibling`              | Go to prev sibling (default `<C-k>`)
`metals.treeviews.gotoNextSibling`              | Go to next sibling (default `<C-j>`)
`metals.treeviews.forceChildrenReload`          | Force the reloading of the children of this node. May be useful when the wrong result is cached and tree contains invalid data. (default `f`)
`metals.treeviews.executeCommandAndOpenTab`     | Execute command and open node under cursor in tab (if node is class, trait and so on) (default `t`)
`metals.treeviews.executeCommandAndOpenSplit`   | Execute command and open node under cursor in horizontal split (if node is class, trait and so on) (default `s`)
`metals.treeviews.executeCommandAndOpenVSplit`  | Execute command and open node under cursor in horizontal split (if node is class, trait and so on) (default `v`)

## Goto Super Method

Depending on whether you're using Vim or Neovim, you'll have a slightly
different behavior with this feature. If you're using Neovim, you'll want to
ensure that you have `codeLens.enable` set to `true` in your `:CocConfig` since
you'll be able to quickly see via code lenses which members are overridden.
Then, you'll be able to simply trigger a code lens action on the line of the
member that is overridden. The default mapping for this is `<leader> cl`.

If you're using Vim, you'll still have access to this functionality, but you'll
have to infer which members are overridden and utilize the
`metals.go-to-super-method` command.

There is also a `metals.super-method-hierarchy` command which will show you the
entire hierarchy of the overridden method.

![Goto super method](https://i.imgur.com/TkjolXq.png)

If you don't utilize this feature you can disable it by setting
`metals.superMethodLensesEnabled` to `false`.

## Run doctor

To troubleshoot problems with your build workspace, open your coc commands by either
using `:CocCommand` or the recommend mapping `<space> c`. This will open your command
window allowing you to search for `metals.doctor-run` command.

![Run Doctor Command](https://i.imgur.com/QaqhxF7.png)

This command opens an embedded doctor in your preview window. If you're not familiar with
having multiple windows, you can use `<C-w> + w` to jump into it.

![Embedded Doctor](https://i.imgur.com/McaAFv5.png)

## Other Available Commands

You can see a full list of the Metals server commands
[here](https://scalameta.org/metals/docs/integrations/new-editor.html#metals-server-commands).

## Show document symbols

Run `:CocList outline` to show a symbol outline for the current file or use the
default mapping `<space> o`.

![Document Symbols](https://i.imgur.com/gEhAXV4.png)

## Available Configuration Options

The following configuration options are currently available. The easiest way to
set these configurations is to enter `:CocConfig` or `:CocLocalConfig` to set
your global or local configuration settings respectively.
If you'd like to get autocompletion help for the configuration values you can
install [coc-json](https://github.com/neoclide/coc-json).

```scala mdoc:user-config:lsp-config-coc
```

```scala mdoc:new-project:vim
```

## Enable on type formatting for multiline string formatting

![on-type](https://i.imgur.com/astTOKu.gif)

To properly support some of the multiline string options like adding `|` in
the multiline string, we are using the `onTypeFormatting` method. To enable the
functionality you need to enable `coc.preferences.formatOnType` setting.

![coc-preferences-formatOnType](https://i.imgur.com/RWPHt2q.png)

## Close buffer without exiting

To close a buffer and return to the previous buffer, run the following command.

```vim
:bd
```

This command is helpful when navigating in library dependency sources in the .metals/readonly directory.

## Shut down the language server

The Metals server is shutdown when you exit vim as usual.

```vim
:wq
```

## Statusline integration

It's recommended to use a statusline integration with `coc-metals` in order to
allow messages to be displayed in your status line rather than as a message.
This will allow for a better experience as you can continue to get status
information while entering a command or responding to a prompt. However, we
realize that not everyone by default will have this setup, and since the user
needs to see messages about the status of their build, the following is
defaulted to false.

```json
"metals.statusBarEnabled": true
```

Again, it's recommended to make this active, and use a statusline plugin, or
manually add the coc status information into your statusline. `coc.nvim` has
multiple ways to integrate with various statusline plugins. You can find
instructions for each of them located
[here](https://github.com/neoclide/coc.nvim/wiki/Statusline-integration).  If
you're unsure of what to use,
[vim-airline](https://github.com/vim-airline/vim-airline) is a great minimal
choice that will work out of the box.

With [vim-airline](https://github.com/vim-airline/vim-airline) you'll notice
two noteworthy things. The first will be that you'll have diagnostic
information on the far right of your screen.

![Diagnostic statusline](https://i.imgur.com/7uNYTYl.png)

You'll also have metals status information in your status bar.

![Status bar info](https://i.imgur.com/eCAgrCn.png)

Without a statusline integration, you'll get messages like you see below.

![No status line](https://i.imgur.com/XF7A1BJ.png)

If you don't use a statusline plugin, but would still like to see this
information, the easiest way is to make sure you have the following in your
`.vimrc`.

```vim
set statusline^=%{coc#status()}%{get(b:,'coc_current_function','')}
```

## Formatting on save

Add the following configuration to `:CocConfig` if you'd like to have `:w` format using Metals and
Scalafmt.

```json
"coc.preferences.formatOnSaveFiletypes": ["scala"]
```
This step cleans up resources that are used by the server.

```scala mdoc:generic

```

## Using an alternative LSP Client

While we recommend using the `coc-metals` extension with `coc.nvim`, or
`nvim-metals` with Neovim, Metals will work with these alternative LSP clients.
Keep in mind that they have varying levels of LSP support, and you need to
bootstrap Metals yourself.

- [`vim-lsc`](https://github.com/natebosch/vim-lsc/): simple installation and written in Vimscript.
- [`LanguageClient-neovim`](https://github.com/autozimu/LanguageClient-neovim/): client written in Rust.
- [`vim-lsp`](https://github.com/prabirshrestha/vim-lsp): simple installation and written in
    Vimscript.

### Generating metals binary

If you only want to use the latest stable version of Metals, the easiest way to
install Metals is using [coursier](https://get-coursier.io/). Once installed you
can simply do a `cs install metals` to install the latest stable version of
Metals. You can then also do a `cs update metals` to update it.

If you'd like to bootstrap your own Metals for a specific version, you're also
able to do so like this:

```scala mdoc:bootstrap:metals-vim vim-lsc

```
The `-Dmetals.client=vim-lsc` flag is there just as a helper to match your
potential client. So make sure it matches your client name. This line isn't
mandatory though as your client can fully be configured via
`InitializationOptions`, which should be easily configurable by your client. You
can read more about his
[here](https://scalameta.org/metals/blog/2020/07/23/configuring-a-client.html#initializationoptions).
