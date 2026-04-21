import { AuthCallbackClient } from "./AuthCallbackClient";

interface AuthCallbackPageProps {
    searchParams?: Promise<Record<string, string | string[] | undefined>>;
}

export default async function AuthCallbackPage({ searchParams }: AuthCallbackPageProps) {
    const resolvedSearchParams = searchParams ? await searchParams : {};
    const statusValue = resolvedSearchParams.status;
    const statusParam = Array.isArray(statusValue) ? statusValue[0] : statusValue ?? null;

    return <AuthCallbackClient statusParam={statusParam} />;
}
