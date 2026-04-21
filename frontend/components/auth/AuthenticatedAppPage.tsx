"use client";

import { type ReactNode, useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { AppShell } from "@/components/app/AppShell";
import { useAuthSessionState } from "@/features/auth/useAuthSessionState";

interface AuthenticatedAppPageProps {
  children: (auth: ReturnType<typeof useAuthSessionState>) => ReactNode;
  shell?: "app" | "none";
}

export const AuthenticatedAppPage = ({ children, shell = "app" }: AuthenticatedAppPageProps) => {
  const auth = useAuthSessionState();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (auth.isLoading) {
      return;
    }

    if (!auth.session?.authenticated) {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [auth.isLoading, auth.session, pathname, router]);

  if (auth.isLoading || !auth.session?.authenticated) {
    return (
      <div className="flex min-h-screen items-center justify-center px-6">
        <div className="surface-card w-full max-w-md px-8 py-10 text-center">
          <Loader2 className="mx-auto h-5 w-5 animate-spin text-[var(--foreground-muted)]" />
          <p className="mt-4 text-sm text-[var(--foreground-muted)]">
            로그인 상태를 확인하고 있습니다.
          </p>
        </div>
      </div>
    );
  }

  if (shell === "none") {
    return <>{children(auth)}</>;
  }

  return <AppShell session={auth.session} onLogout={auth.logout}>{children(auth)}</AppShell>;
};
