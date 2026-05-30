import type { NextConfig } from "next";

// Local development runs the Spring Boot API on 8080. The root Cloud Run
// Dockerfile still overrides this to the internal 8081 backend port.
const backendInternalUrl = process.env.BACKEND_INTERNAL_URL ?? "http://127.0.0.1:8080";

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
