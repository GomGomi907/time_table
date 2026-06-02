import type { Metadata } from "next";
import { Noto_Sans_KR } from "next/font/google";
import type { ReactNode } from "react";

import { BodyScrollLockController } from "@/components/body-scroll-lock-controller";
import { NoticeCenter } from "@/components/notice-center";
import { QueryProvider } from "@/components/query-provider";

import "./globals.css";

const bodyFont = Noto_Sans_KR({
  subsets: ["latin"],
  weight: ["400", "500", "700", "800"],
  variable: "--font-body",
});

export const metadata: Metadata = {
  title: "Time Table",
  description: "Execution workspace for weekly planning, focus, and approval-first schedule adjustment.",
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
