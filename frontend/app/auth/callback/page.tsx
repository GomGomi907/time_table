import { Suspense } from "react";

import { AuthCallbackView } from "@/components/auth-callback-view";

function AuthCallbackFallback() {
  return (
    <div className="status-screen">
      <div className="status-panel">
        <p className="eyebrow">로그인</p>
        <h1>Time Table 작업공간을 준비하고 있습니다.</h1>
        <p>로그인을 마치고 다음 화면으로 이동합니다.</p>
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
