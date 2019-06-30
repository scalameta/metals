---
id: vim
title: Vim
---

Metals works with most LSP clients for Vim:

- [`vim-lsc`](https://github.com/natebosch/vim-lsc/): simple installation and
  low resource usage but limited functionality (no auto-import, cancellation,
  formatting, folding).
- [`coc.nvim`](https://github.com/neoclide/coc.nvim): installation requires
  neovim or Vim v8.1 along with npm. Feature rich, supports all of LSP.
- [`LanguageClient-neovim`](https://github.com/autozimu/LanguageClient-neovim/):
  client written in Rust.
- [`vim-lsp`](https://github.com/prabirshrestha/vim-lsp): simple installation
  but limited functionality (no auto-import, cancellation and no prompt for
  build import).

In this page, we use `coc.nvim` (Conquer Of Completion) since it offers a richer
user experience but the same steps can be adapted to use Metals with other LSP
clients.

![Vim demo](https://i.imgur.com/jMMEmCC.gif)

```scala mdoc:requirements

```

## Installing Vim

The coc.nvim plugin requires either **Vim >= 8.1** or **Neovim >= 0.3.1**. Make
sure you have the correct version installed.

```sh
# If using Vim
vim --version | head
VIM - Vi IMproved 8.1

# If using Neovim
nvim --version | head
NVIM v0.3.7
```

## Installing yarn

`coc.nvim` requires [Node.js](https://nodejs.org/en/download/) in order to work.
It also uses [Yarn](https://yarnpkg.com/en/docs/install#debian-stable) to manage
extensions but you could opt-out of it and use `vim-plug` instead for example.

For convenience we recommend installing both:

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
plugin manager like vundle works too), update `~/.vimrc` to include the
following settings.

```vim
" ~/.vimrc

" Configuration for vim-plug
Plug 'derekwyatt/vim-scala'
Plug 'neoclide/coc.nvim', {'do': { -> coc#util#install()}}

" Configuration for vim-scala
au BufRead,BufNewFile *.sbt set filetype=scala
```

Run `:PlugInstall` to install the plugin. If you already have `coc.nvim`
installed, be sure to update to the latest version with `:PlugUpdate`.

### Configuration

We need to tell `coc.nvim` that our LSP server is going to be `metals`. In order
to do so, we need to run `:CocConfig` and input our configuration. Here's the
recommended default:

```jsonc
// vim:    ~/.vim/coc-settings.json
// neovim: ~/.config/nvim/coc-settings.json
{
  "languageserver": {
    "metals": {
      "command": "metals-vim",
      "rootPatterns": ["build.sbt"],
      "filetypes": ["scala", "sbt"]
    }
  }
}
```

`coc.nvim` uses [jsonc](https://code.visualstudio.com/docs/languages/json) as
configuration file format. It's basically json with comments support.

In order to get comments highlighting, please add:

```vim
autocmd FileType json syntax match Comment +\/\/.\+$+
```

### Generating metals binary

If you now start Vim in a Scala project then it will fail since the `metals-vim`
binary does not exist yet.

```scala mdoc:bootstrap:metals-vim coc.nvim

```

The `-Dmetals.client=coc.nvim` flag is important since it configures Metals for
usage with the `coc.nvim` client.

```scala mdoc:editor:vim

```

### LSP commands key mapping

`coc.nvim` doesn't come with a default key mapping for LSP commands, you need to
configure it yourself.

Here's a recommended configuration:

```vim
" ~/.vimrc
" Configuration for coc.nvim

" Smaller updatetime for CursorHold & CursorHoldI
set updatetime=300

" don't give |ins-completion-menu| messages.
set shortmess+=c

" always show signcolumns
set signcolumn=yes

" Some server have issues with backup files, see #649
set nobackup
set nowritebackup

" Better display for messages
set cmdheight=2

" Use <c-space> for trigger completion.
inoremap <silent><expr> <c-space> coc#refresh()

" Use <cr> for confirm completion, `<C-g>u` means break undo chain at current position.
" Coc only does snippet and additional edit on confirm.
inoremap <expr> <cr> pumvisible() ? "\<C-y>" : "\<C-g>u\<CR>"

" Use `[c` and `]c` for navigate diagnostics
nmap <silent> [c <Plug>(coc-diagnostic-prev)
nmap <silent> ]c <Plug>(coc-diagnostic-next)

" Remap keys for gotos
nmap <silent> gd <Plug>(coc-definition)
nmap <silent> gy <Plug>(coc-type-definition)
nmap <silent> gi <Plug>(coc-implementation)
nmap <silent> gr <Plug>(coc-references)

" Remap for do codeAction of current line
nmap <leader>ac <Plug>(coc-codeaction)

" Remap for do action format
nnoremap <silent> F :call CocAction('format')<CR>

" Use K for show documentation in preview window
nnoremap <silent> K :call <SID>show_documentation()<CR>

function! s:show_documentation()
  if &filetype == 'vim'
    execute 'h '.expand('<cword>')
  else
    call CocAction('doHover')
  endif
endfunction

" Highlight symbol under cursor on CursorHold
autocmd CursorHold * silent call CocActionAsync('highlight')

" Remap for rename current word
nmap <leader>rn <Plug>(coc-rename)

" Show all diagnostics
nnoremap <silent> <space>a  :<C-u>CocList diagnostics<cr>
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
```

## Learn more about coc.nvim

For comprehensive documentation about `coc.nvim`, run the following command.

```vim
:help coc-contents
```

## List all workspace compile errors

To list all compilation errors and warnings in the workspace, run the following
command.

```vim
:CocList diagnostics
```

Or use the default recommended mapping `<space> a`.

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
:call CocRequestAsync('metals', 'workspace/executeCommand', { 'command': 'doctor-run' })
```

This command opens your browser with a table like this.

![Run Doctor](https://i.imgur.com/yelm0jd.png)

Note: the binary `metals-vim` needs to be built using `-Dmetals.http=true` for
this command to work.

## Manually start build import

To manually start the `sbt bloopInstall` step, call the following command below.
This command works only for sbt builds at the moment.

```vim
:call CocRequestAsync('metals', 'workspace/executeCommand', { 'command': 'build-import' })
```

## Manually connect with build server

To manually tell Metals to establish a connection with the build server, call
the command below. This command works only at the moment if there is a `.bloop/`
directory containing JSON files.

```vim
:call CocRequestAsync('metals', 'workspace/executeCommand', { 'command': 'build-connect' })
```

## Show document symbols

Run `:CocList outline` to show a symbol outline for the current file or use the
default key `<space> o`.

![Document Symbols](https://i.imgur.com/LviFAVm.gif)

```scala mdoc:generic

```
