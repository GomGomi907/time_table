import { notFound } from "next/navigation";

import { DevVisualLoginView } from "@/components/dev-visual-login-view";

export default function DevVisualLoginPage() {
  if (process.env.NODE_ENV === "production") {
    notFound();
  }

  return <DevVisualLoginView />;
}
