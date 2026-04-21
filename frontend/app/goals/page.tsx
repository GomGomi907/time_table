"use client";

import { AuthenticatedAppPage } from "@/components/auth/AuthenticatedAppPage";
import { GoalsWorkspace } from "@/features/goals/GoalsWorkspace";

export default function GoalsPage() {
  return (
    <AuthenticatedAppPage>
      {() => <GoalsWorkspace />}
    </AuthenticatedAppPage>
  );
}
