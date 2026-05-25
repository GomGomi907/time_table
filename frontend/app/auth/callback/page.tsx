import { Suspense } from "react";

import { AuthCallbackView } from "@/components/auth-callback-view";

function AuthCallbackFallback() {
  return (
    <div className="status-screen">
      <div className="status-panel">
        <p className="eyebrow">계정 연결</p>
        <h1>Time Table 작업공간을 준비하고 있습니다.</h1>
        <p>연결된 계정을 확인한 뒤 다음 화면으로 이어집니다.</p>
      </div>
    </div>
  );
}

export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<AuthCallbackFallback />}>
      <AuthCallbackView />
    </Suspense>
  );
}
