import { Suspense } from "react";
import { notFound } from "next/navigation";

import { DevVisualLoginView } from "@/components/dev-visual-login-view";

export default function DevVisualLoginPage() {
  if (process.env.NODE_ENV === "production" && process.env.NEXT_PUBLIC_ENABLE_VISUAL_LOGIN !== "true") {
    notFound();
  }

  return (
    <Suspense fallback={null}>
      <DevVisualLoginView />
    </Suspense>
  );
}
