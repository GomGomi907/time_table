import { Suspense } from "react";

import { AuthCallbackView } from "@/components/auth-callback-view";

function AuthCallbackFallback() {
  return (
    <div className="status-screen">
      <div className="status-panel">
        <p className="eyebrow">로그인 콜백</p>
        <h1>로그인 상태를 확인 중입니다.</h1>
        <p>콜백 쿼리를 읽고 세션을 복구하는 중입니다.</p>
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
