"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { useBodyScrollLock } from "@/lib/use-body-scroll-lock";

interface ConfirmDialogProps {
  title: string;
  description: ReactNode;
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
  const panelRef = useRef<HTMLElement | null>(null);
  const cancelButtonRef = useRef<HTMLButtonElement | null>(null);
  useBodyScrollLock(true);

  useEffect(() => {
    cancelButtonRef.current?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !isPending) {
        event.stopPropagation();
        onCancel();
        return;
      }

      if (event.key === "Tab") {
        const focusableElements = panelRef.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        );
        if (!focusableElements?.length) {
          return;
        }

        const firstElement = focusableElements[0];
        const lastElement = focusableElements[focusableElements.length - 1];
        if (event.shiftKey && document.activeElement === firstElement) {
          event.preventDefault();
          lastElement.focus();
          return;
        }
        if (!event.shiftKey && document.activeElement === lastElement) {
          event.preventDefault();
          firstElement.focus();
        }
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isPending, onCancel]);

  return (
    <div className="modal-backdrop" role="presentation" onClick={onCancel}>
      <section
        ref={panelRef}
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
