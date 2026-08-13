import { Building2, Github } from "lucide-react";
import { siteConfig } from "@/lib/site";

export interface DownloadOption {
  id: string;
  title: string;
  description: string;
  icon: typeof Github;
  buttonText: string;
  href: string;
  badge?: string;
}

export const downloads: DownloadOption[] = [
  {
    id: "stable",
    title: "Stable Release",
    description: "Download the latest stable version directly from GitHub.",
    icon: Github,
    buttonText: "Download APK",
    href: siteConfig.links.releases,
  },
  {
    id: "preview",
    title: "Preview Builds",
    description: "Test the latest features and improvements in development.",
    icon: Building2,
    buttonText: "View Pre-releases",
    href: siteConfig.links.releases,
  },
];
