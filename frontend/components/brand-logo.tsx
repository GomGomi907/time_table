interface BrandLogoProps {
  className?: string;
}

export function BrandLogo({ className }: BrandLogoProps) {
  return (
    <img
      alt="Time Table 로고"
      className={className}
      decoding="async"
      height={512}
      src="/brand/time-table-logo.svg"
      width={512}
    />
  );
}
