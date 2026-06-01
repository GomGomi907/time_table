"use client";

import { useEffect, useRef } from "react";
import { useBodyScrollLock } from "@/lib/use-body-scroll-lock";

interface ConfirmDialogProps {
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  isPending?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  cancelLabel = "취소",
  isPending = false,
  onCancel,
  onConfirm,
}: ConfirmDialogProps) {
  const cancelButtonRef = useRef<HTMLButtonElement | null>(null);
  useBodyScrollLock(true);

  useEffect(() => {
    cancelButtonRef.current?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !isPending) {
        event.stopPropagation();
        onCancel();
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isPending, onCancel]);

  return (
    <div className="modal-backdrop" role="presentation" onClick={onCancel}>
      <section
        className="modal-panel confirm-dialog-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-description"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="modal-header">
          <div>
            <p className="panel-kicker">확인</p>
            <h2 id="confirm-dialog-title">{title}</h2>
          </div>
        </div>
        <p className="confirm-dialog-copy" id="confirm-dialog-description">
          {description}
        </p>
        <div className="modal-actions">
          <button ref={cancelButtonRef} className="ghost-btn" disabled={isPending} type="button" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button className="ghost-btn danger-btn" disabled={isPending} type="button" onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </section>
    </div>
  );
}
