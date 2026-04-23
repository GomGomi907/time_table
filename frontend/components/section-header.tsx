import { ReactNode } from "react";

interface SectionHeaderProps {
  eyebrow: string;
  title: string;
  description?: ReactNode;
  trailing?: ReactNode;
}

export function SectionHeader({
  eyebrow,
  title,
  description,
  trailing,
}: SectionHeaderProps) {
  return (
    <div className="section-header">
      <div className="section-header-copy">
        <p className="panel-kicker">{eyebrow}</p>
        <h2>{title}</h2>
        {description ? <p className="section-header-note">{description}</p> : null}
      </div>
      {trailing ? <div className="section-header-meta">{trailing}</div> : null}
    </div>
  );
}
