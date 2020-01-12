---
id: vim
title: Vim
---

![Vim demo](https://i.imgur.com/4BYHCCL.gif)

Metals works with most LSP clients for Vim, but we recommend using the
[coc-metals extension](https://github.com/scalameta/coc-metals) for [`coc.nvim`](https://github.com/neoclide/coc.nvim)
which will provide the most complete implementation of LSP and Metals-specific helpers.

```scala mdoc:requirements

```

## Installing Vim

The coc.nvim plugin requires either **Vim >= 8.1** or **Neovim >= 0.3.1**. Make
sure you have the correct version installed. While it works with both Vim and Neovim,
we recommend using Neovim since it provides a smoother experience with some of the features such
as code actions and general performance.

```sh
# If using Vim
vim --version | head
VIM - Vi IMproved 8.2

# If using Neovim
nvim --version | head
NVIM v0.4.3
```
## Installing yarn

`coc.nvim` requires [Node.js](https://nodejs.org/en/download/) in order to work.
It also uses [Yarn](https://yarnpkg.com/en/docs/install#debian-stable) to manage
extensions but you could opt-out of it and use `vim-plug` instead.

For convenience we recommend installing both via your favorite package manager or manually:

```sh
curl -sL install-node.now.sh/lts | sh
curl --compressed -o- -L https://yarnpkg.com/install.sh | bash
```

## Installing coc.nvim

Once the requirements are satisfied, we can now proceed to install the following
plugins:

- [`neoclide/coc.nvim`](https://github.com/neoclide/coc.nvim/): Language Server
  Protocol client to communicate with the Metals language server.
- [`derekwyatt/vim-scala`](https://github.com/derekwyatt/vim-scala): for syntax
  highlighting Scala and sbt source files.

Assuming [`vim-plug`](https://github.com/junegunn/vim-plug) is used (another
plugin manager like vundle works too), update your `~/.vimrc` to include the
following settings.

```vim
" ~/.vimrc

" Configuration for vim-plug
Plug 'derekwyatt/vim-scala'
Plug 'neoclide/coc.nvim', {'branch': 'release'}

" Configuration for vim-scala
au BufRead,BufNewFile *.sbt set filetype=scala
```

Run `:PlugInstall` to install the plugin. If you already have `coc.nvim`
installed, be sure to update to the latest version with `:PlugUpdate`.

`coc.nvim` uses [jsonc](https://code.visualstudio.com/docs/languages/json) as
a configuration file format. It's basically json with comment support.

In order to get comment highlighting, please add:

```vim
autocmd FileType json syntax match Comment +\/\/.\+$+
```
### LSP commands key mapping

`coc.nvim` doesn't come with a default key mapping for LSP commands, so you need to
configure it yourself.

Here's a recommended configuration:

```vim
" ~/.vimrc
" Configuration for coc.nvim

set hidden

" Some servers have issues with backup files
set nobackup
set nowritebackup

" Better display for messages
set cmdheight=2

" You will have a bad experience with diagnostic messages with the default 4000.
set updatetime=300

" Don't give |ins-completion-menu| messages.
set shortmess+=c

" Always show signcolumns
set signcolumn=yes

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
inoremap <silent><expr> <c-space> coc#refresh()

" Use <cr> to confirm completion, `<C-g>u` means break undo chain at current position.
" Coc only does snippet and additional edit on confirm.
inoremap <expr> <cr> pumvisible() ? "\<C-y>" : "\<C-g>u\<CR>"

" Use `[c` and `]c` to navigate diagnostics
nmap <silent> [c <Plug>(coc-diagnostic-prev)
nmap <silent> ]c <Plug>(coc-diagnostic-next)

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
  else
    call CocAction('doHover')
  endif
endfunction

" Highlight symbol under cursor on CursorHold
autocmd CursorHold * silent call CocActionAsync('highlight')

" Remap for rename current word
nmap <leader>rn <Plug>(coc-rename)

" Remap for format selected region
xmap <leader>f  <Plug>(coc-format-selected)
nmap <leader>f  <Plug>(coc-format-selected)

augroup mygroup
  autocmd!
  " Setup formatexpr specified filetype(s).
  autocmd FileType scala setl formatexpr=CocAction('formatSelected')
  " Update signature help on jump placeholder
  autocmd User CocJumpPlaceholder call CocActionAsync('showSignatureHelp')
augroup end

" Remap for do codeAction of selected region, ex: `<leader>aap` for current paragraph
xmap <leader>a  <Plug>(coc-codeaction-selected)
nmap <leader>a  <Plug>(coc-codeaction-selected)

" Remap for do codeAction of current line
nmap <leader>ac  <Plug>(coc-codeaction)
" Fix autofix problem of current line
nmap <leader>qf  <Plug>(coc-fix-current)

" Use `:Format` to format current buffer
command! -nargs=0 Format :call CocAction('format')

" Use `:Fold` to fold current buffer
command! -nargs=? Fold :call     CocAction('fold', <f-args>)

" Add status line support, for integration with other plugin, checkout `:h coc-status`
set statusline^=%{coc#status()}%{get(b:,'coc_current_function','')}

" Show all diagnostics
nnoremap <silent> <space>a  :<C-u>CocList diagnostics<cr>
" Manage extensions
nnoremap <silent> <space>e  :<C-u>CocList extensions<cr>
" Show commands
nnoremap <silent> <space>c  :<C-u>CocList commands<cr>
" Find symbol of current document
nnoremap <silent> <space>o  :<C-u>CocList outline<cr>
" Search workspace symbols
nnoremap <silent> <space>s  :<C-u>CocList -I symbols<cr>
" Do default action for next item.
nnoremap <silent> <space>j  :<C-u>CocNext<CR>
" Do default action for previous item.
nnoremap <silent> <space>k  :<C-u>CocPrev<CR>
" Resume latest coc list
nnoremap <silent> <space>p  :<C-u>CocListResume<CR>

" Notify coc.nvim that <enter> has been pressed.
" Currently used for the formatOnType feature.
inoremap <silent><expr> <cr> pumvisible() ? coc#_select_confirm()
      \: "\<C-g>u\<CR>\<c-r>=coc#on_enter()\<CR>"
```

### Installing coc-metals

Once you have `coc.nvim` installed, you can then install Metals a few different ways. The easiest is
by running:

```vim
:CocInstall coc-metals
```

If you'd like to use the latest changes on master, you can also use the url of the repo in the
command like so:

```vim
:CocInstall https://github.com/scalameta/coc-metals
```
If you'd like to use the latest changes on master, but would prefer managing the plugin using a plugin
manager to download the extension, make sure you run the below snippet to uninstall the old version
first.

```vim
:CocUninstall coc-metals
```

Then, if you are using [`vim-plug`](https://github.com/junegunn/vim-plug)
for example, enter the following into where you manage your plugins:

```vim
Plug 'scalameta/coc-metals', {'do': 'yarn install --frozen-lockfile'}
```
Then, issue a `:PlugInstall` to install the extension, and regularly a `:PlugUpdate` to update it
and pull in the latest changes. If you're relying on `coc.nvim` to install the extension, it will
automatically pull in the latest versions when published.

```scala mdoc:editor:vim
Update the `metals.sbtScript` setting to use a custom `sbt` script instead of the
default Metals launcher if you need further customizations like reading environment
variables.

![Sbt Launcher](https://i.imgur.com/kbxNKzI.png)
```

## Configure Java version
The `coc-metals` extension uses by default the `JAVA_HOME` environment variable (via [`find-java-home`](https://www.npmjs.com/package/find-java-home)) to locate the `java` executable.

![No Java Home](https://i.imgur.com/clDfPMk.png)

If no `JAVA_HOME` is detected you can then Open Settings by following the instructions or do it at a later time by using `:CocConfig` or `:CocConfigLocal` which will open up your configuration where you can manually enter your JAVA_HOME location.

![Enter Java Home](https://i.imgur.com/wVThrMq.png)

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

## Worksheets

Metals allows users to create a `*.worksheet.sc` file and see evaluations right in the file. In Vim,
this is done using comments that are inserted which will allow you to hover on them to expand. In
Neovim, this is done using Neovim's [virtual text](https://neovim.io/doc/user/api.html#nvim_buf_set_virtual_text())
to implement Metal's [Decoration Protocol](https://scalameta.org/metals/docs/editors/decoration-protocol.html).
If using Neovim, make sure to have the following line in included in your `.vimrc` along with your `coc.nvim` mappings.

```vim
nmap <Leader>ws <Plug>(coc-metals-expand-decoration)
```
Then, when on the line that you'd like to expand the decoration to get the hover information, execute a
`<leader>ws` in order to see the expanded text for that line.

![Decorations with worksheets](https://i.imgur.com/X8bVmu8.png)

## Run doctor

To troubleshoot problems with your build workspace, open your coc commands by either
using `:CocCommand` or the recommend mapping `<space> c`. This will open your command
window allowing you to search for `metals.doctor-run` command.

![Run Doctor Command](https://i.imgur.com/QaqhxF7.png)

This command opens an embedded doctor in your preview window. If you're not familiar with
having multiple windows, you can use `<C-w> + w` to jump into it.

![Embedded Doctor](https://i.imgur.com/McaAFv5.png)

## Other Available Command

  - `metals.restartServer`
  - `metals.build-import`
  - `metals.build-connect`
  - `metals.sources-scan`
  - `metals.compile-cascade`
  - `metals.compile-cancel`
  - `metals.doctor-run`
  - `metals.logs-toggle`

## Show document symbols

Run `:CocList outline` to show a symbol outline for the current file or use the
default mapping `<space> o`.

![Document Symbols](https://i.imgur.com/gEhAXV4.png)

## Available Configuration Options

The following configuration options are currently available. The easiest way to set these configurations is to enter `:CocConfig` or `:CocLocalConfig` to set your global or local configuration settings respectively.
If you'd like to get autocompletion help for the configuration values you can install [coc-json](https://github.com/neoclide/coc-json).

```scala mdoc:user-config:lsp-config-coc
```

## Enable on type formatting for multiline string formatting

![on-type](https://i.imgur.com/astTOKu.gif)

To properly support adding `|` in multiline strings we are using the
`onTypeFormatting` method. To enable the functionality you need to enable
`coc.preferences.formatOnType` setting.

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

`coc.nvim` has multiple ways to integrate with various statusline plugins. You can find instructions
for each of them located [here](https://github.com/neoclide/coc.nvim/wiki/Statusline-integration).
Two noteworthy things that they add are the ability to see diagnostic information in the current
buffer.

![Diagnostic statusline](https://i.imgur.com/7uNYTYl.png)

And it also shows progress information for longer standing processes.

![Progress item](https://i.imgur.com/AAWZ4o4.png)

If you don't use a statusline integration, but would still like to see this information, the easiest
way is to add the following to your `.vimrc`.

```vim
set statusline^=%{coc#status()}
```
The `coc#status()` function will display both status and diagnostic information. However, if you are
using an integration like I am in the photos that display your diagnostic information in the far
right, but you'd like to see the status information in the middle, you can make a small function to
just grab that information, and use it in your statusline. Here is an example for lightline to
display only the status information in the middle of the statusline (`section_c`).

```vim
function! CocExtensionStatus() abort
  return get(g:, 'coc_status', '')
endfunction
let g:airline_section_c = '%f%{CocExtensionStatus()}'
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

While we recommend using the `coc-metals` extension with `coc.nvim`, Metals will work
with these alternative LSP clients. Keep in mind that they have varying levels of LSP support.

- [`vim-lsc`](https://github.com/natebosch/vim-lsc/): simple installation and written in Vimscript.
- [`LanguageClient-neovim`](https://github.com/autozimu/LanguageClient-neovim/): client written in Rust.
- [`vim-lsp`](https://github.com/prabirshrestha/vim-lsp): simple installation and written in
    Vimscript.

### Generating metals binary

If you now start Vim in a Scala project, it will fail since the `metals-vim`
binary does not exist yet.

```scala mdoc:bootstrap:metals-vim vim-lsc

```

The `-Dmetals.client=vim-lsc` flag is important since it configures Metals for
usage with the `vim-lsc` client.
