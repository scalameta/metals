module.exports = {
  "title": "Metals",
  "tagline": "Scala language server with rich IDE features",
  "url": "http://scalameta.org",
  "baseUrl": "/metals/",
  "organizationName": "scalameta",
  "projectName": "metals",
  "favicon": "img/favicon.ico",
  "customFields": {
    "repoUrl": "https://github.com/scalameta/metals"
  },
  "staticDirectories": ["static", "../website/target/data"],
  "onBrokenLinks": "log",
  "onBrokenMarkdownLinks": "log",
  "presets": [
    [
      "@docusaurus/preset-classic",
      {
        "docs": {
          "path": "../website/target/docs",
          "showLastUpdateAuthor": true,
          "showLastUpdateTime": true,
          "editUrl": ({ docPath }) => `https://github.com/scalameta/metals/edit/main/docs/${docPath}`,
          "sidebarPath": "../website/sidebars.json"
        },
        "blog": {
          "path": "blog",
          "postsPerPage": 1,
          "blogSidebarCount": "ALL",
          "blogSidebarTitle": "All Blog Posts",
        },
        "theme": {
          "customCss": "../src/css/customTheme.css"
        },
        "gtag": {
          "trackingID": "UA-140140828-1"
        }
      }
    ]
  ],
  "plugins": [
    [
      "@docusaurus/plugin-client-redirects",
      {
        "fromExtensions": [
          "html"
        ],
        "redirects": [
          {
            "to": "/docs/",
            "from": "/docs/editors/overview.html"
          }
        ]
      }
    ]
  ],
  "themeConfig": {
    "prism": {
      "additionalLanguages": ["lisp"],
    },
    "colorMode": {
      "switchConfig": {
        "darkIcon": "🌙",
        "lightIcon": "☀️"
      }
    },
    "navbar": {
      "title": "Metals",
      "logo": {
        "src": "img/scalameta-logo.png"
      },
      "items": [
        {
          "to": "docs/",
          "label": "Docs",
          "position": "left"
        },
        {
          "to": "blog/",
          "label": "Blog",
          "position": "left"
        },
        {
          "href": "https://github.com/scalameta/metals",
          "label": "GitHub",
          "position": "left"
        }
      ]
    },
    "image": "img/scalameta-logo.png",
    "footer": {
      "links": [{
        "title": "Overview",
        "items": [
          {
            "label": "Overview",
            "to": "docs/overview/editor-support"
          },
          {
            "label": "Build Tools",
            "to": "docs/build-tools/overview"
          },
          {
            "label": "Project Goals",
            "to": "docs/contributors/project-goals"
          },
          {
            "label": "Contributing",
            "to": "docs/contributors/getting-started"
          }
        ]
      },
      {
        "title": "Social",
        "items": [
          {
            "html": `<a href="https://github.com/scalameta/metals" target="_blank">
                      <img src="https://img.shields.io/github/stars/scalameta/metals.svg?color=%23087e8b&label=stars&logo=github&style=social" />
                    </a>`
          },
          {
            "html": `<a href = "https://discord.gg/RFpSVth" target = "_blank" >
                      <img src="https://img.shields.io/discord/632642981228314653?logo=discord&style=social" />
                    </a>`
          },
          {
            "html": `<a href="https://twitter.com/scalameta" target="_blank">
                      <img src="https://img.shields.io/twitter/follow/scalameta.svg?logo=twitter&style=social" />
                    </a>`
          }
        ]
      }],
      "copyright": `Copyright © ${new Date().getFullYear()} Metals`,
      "logo": {
        "src": "img/scalameta-logo.png"
      }
    },
    "algolia": {
      "apiKey": "c865f6d974a3072a35d4b53d48ac2307",
      "indexName": "metals"
    }
  }
}
