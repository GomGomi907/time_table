import type { Metadata } from "next";
import { Noto_Sans_KR } from "next/font/google";
import type { ReactNode } from "react";

import { BodyScrollLockController } from "@/components/body-scroll-lock-controller";
import { NoticeCenter } from "@/components/notice-center";
import { QueryProvider } from "@/components/query-provider";

import "./globals.css";

const siteUrl = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

const bodyFont = Noto_Sans_KR({
  subsets: ["latin"],
  weight: ["400", "500", "700", "800"],
  variable: "--font-body",
});

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: "Time Table",
  description: "주간 계획, 오늘 실행, Google 일정 동기화를 한 화면에서 운영하는 일정 워크스페이스입니다.",
  icons: {
    icon: [
      { url: "/brand/time-table-logo.svg", type: "image/svg+xml" },
      { url: "/brand/time-table-logo-512.png", sizes: "512x512", type: "image/png" },
    ],
    apple: "/brand/time-table-logo-512.png",
  },
  openGraph: {
    title: "Time Table",
    description: "주간 계획, 오늘 실행, Google 일정 동기화를 한 화면에서 운영하는 일정 워크스페이스입니다.",
    images: [{ url: "/brand/time-table-logo-512.png", width: 512, height: 512, alt: "Time Table 로고" }],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: ReactNode;
}>) {
  return (
    <html lang="ko">
      <body className={bodyFont.variable}>
        <QueryProvider>
          <BodyScrollLockController />
          <NoticeCenter />
          {children}
        </QueryProvider>
      </body>
    </html>
  );
}
