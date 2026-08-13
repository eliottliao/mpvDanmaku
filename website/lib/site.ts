/**
 * @file site.ts
 * @description Central configuration file for the website's metadata, links, and constant values.
 * @module lib/site
 */

/**
 * Global site configuration object.
 * Contains metadata, external links, and author information used throughout the application.
 */
const repository = process.env.NEXT_PUBLIC_GITHUB_REPOSITORY ?? "";
const githubUrl = repository ? `https://github.com/${repository}` : "#";

export const siteConfig = {
  name: "mpvDanmaku",
  version: "v0.1.0",
  description:
    "Independent Android media player built on mpvEx with synchronized danmaku support.",
  url: process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000",
  ogImage: `${process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000"}/og.jpg`,
  icons: {
    icon: "/icon.svg",
    apple: "/apple-icon.png",
  },
  links: {
    github: githubUrl,
    releases: repository ? `${githubUrl}/releases` : "#",
    latestRelease: repository ? `${githubUrl}/releases/latest` : "#",
    contributors: repository ? `${githubUrl}/graphs/contributors` : "#",
  },
} as const;

export type SiteConfig = typeof siteConfig;
