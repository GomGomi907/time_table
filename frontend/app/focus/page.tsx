"use client";

import { AuthenticatedAppPage } from "@/components/auth/AuthenticatedAppPage";
import { FocusWorkspace } from "@/features/focus/FocusWorkspace";

export default function FocusPage() {
  return (
    <AuthenticatedAppPage>
      {() => <FocusWorkspace />}
    </AuthenticatedAppPage>
  );
}
