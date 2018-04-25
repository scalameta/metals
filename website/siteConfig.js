// See https://docusaurus.io/docs/site-config.html for all the possible
// site configuration options.

/* List of projects/orgs using your project for the users page */
// const users = [
//   {
//     caption: 'User1',
//     image: '/test-site/img/docusaurus.svg',
//     infoLink: 'https://www.facebook.com',
//     pinned: true,
//   },
// ];

const repoUrl = 'https://github.com/scalameta/metals';

const siteConfig = {
  title: 'Metals',
  tagline: 'A Language Server for Scala',
  url: 'http://scalameta.org',
  baseUrl: '/metals/',

  // Used for publishing and more
  projectName: 'Metals',
  organizationName: 'Scalameta',

  // For no header links in the top nav bar -> headerLinks: [],
  headerLinks: [
    {doc: 'installation-contributors', label: 'Docs'},
    {href: repoUrl, label: 'GitHub', external: true},
  ],

  // If you have users set above, you add it here:
  // users,

  /* path to images for header/footer */
  headerIcon: 'img/scalameta-logo.png',
  footerIcon: 'img/scalameta-logo.png',
  favicon: 'img/favicon.ico',

  /* colors for website */
  colors: {
    primaryColor: '#087E8B',
    secondaryColor: '#1B555C',
  },

  /* custom fonts for website */
  /*fonts: {
    myFont: [
      "Times New Roman",
      "Serif"
    ],
    myOtherFont: [
      "-apple-system",
      "system-ui"
    ]
  },*/
  customDocsPath: 'website/target/docs',

  // This copyright info is used in /core/Footer.js and blog rss/atom feeds.
  copyright:
    'Copyright © ' +
    new Date().getFullYear() +
    ' Metals',

  highlight: {
    // Highlight.js theme to use for syntax highlighting in code blocks
    theme: 'github',
  },

  // Add custom scripts here that would be placed in <script> tags
  // scripts: ['https://buttons.github.io/buttons.js'],

  /* On page navigation for the current documentation page */
  onPageNav: 'separate',

  /* Open Graph and Twitter card images */
  ogImage: 'img/scalameta-logo.png',
  twitterImage: 'img/scalameta-logo.png',

  editUrl: `${repoUrl}/edit/master/docs/`,

  repoUrl
};

module.exports = siteConfig;
