"use client";

import { AuthenticatedAppPage } from "@/components/auth/AuthenticatedAppPage";
import { TodayWorkspaceMockup } from "@/features/mockups/TodayWorkspaceMockup";

export default function TodayWorkspaceMockupPage() {
  return (
    <AuthenticatedAppPage shell="none">
      {() => <TodayWorkspaceMockup />}
    </AuthenticatedAppPage>
  );
}
