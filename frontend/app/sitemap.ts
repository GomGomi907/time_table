import type { MetadataRoute } from "next";

const siteUrl = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";
const lastModified = new Date("2026-06-12T00:00:00.000Z");

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    { url: siteUrl, lastModified, changeFrequency: "weekly", priority: 1 },
    { url: `${siteUrl}/login`, lastModified, changeFrequency: "weekly", priority: 0.8 },
    { url: `${siteUrl}/privacy`, lastModified, changeFrequency: "monthly", priority: 0.5 },
    { url: `${siteUrl}/terms`, lastModified, changeFrequency: "monthly", priority: 0.5 },
  ];
}
