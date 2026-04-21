import { LoginScreen } from "@/components/auth/LoginScreen";

interface LoginPageProps {
  searchParams?: Promise<Record<string, string | string[] | undefined>>;
}

export default async function LoginPage({ searchParams }: LoginPageProps) {
  const resolvedSearchParams = searchParams ? await searchParams : {};
  const nextValue = resolvedSearchParams.next;
  const nextPath = Array.isArray(nextValue) ? nextValue[0] : nextValue;

  return <LoginScreen nextPath={nextPath ?? "/dashboard"} />;
}
