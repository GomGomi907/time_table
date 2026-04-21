"use client";

import { AuthenticatedAppPage } from "@/components/auth/AuthenticatedAppPage";
import { SettingsWorkspace } from "@/features/settings/SettingsWorkspace";

export default function SettingsPage() {
  return (
    <AuthenticatedAppPage>
      {(auth) => <SettingsWorkspace auth={auth} />}
    </AuthenticatedAppPage>
  );
}
