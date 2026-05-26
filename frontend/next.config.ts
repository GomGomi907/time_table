import type { NextConfig } from "next";

const backendInternalUrl = process.env.BACKEND_INTERNAL_URL ?? "http://127.0.0.1:8081";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  devIndicators: false,
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${backendInternalUrl}/api/:path*`,
      },
      {
        source: "/oauth2/:path*",
        destination: `${backendInternalUrl}/oauth2/:path*`,
      },
      {
        source: "/login/oauth2/:path*",
        destination: `${backendInternalUrl}/login/oauth2/:path*`,
      },
      {
        source: "/actuator/:path*",
        destination: `${backendInternalUrl}/actuator/:path*`,
      },
    ];
  },
};

export default nextConfig;
