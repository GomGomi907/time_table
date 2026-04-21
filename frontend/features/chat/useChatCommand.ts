"use client";

import { useChatCommandStore } from "./storeChatCommand";

export const useChatCommand = () => {
  const lastResponse = useChatCommandStore((state) => state.lastResponse);
  const history = useChatCommandStore((state) => state.history);
  const isSubmitting = useChatCommandStore((state) => state.isSubmitting);
  const error = useChatCommandStore((state) => state.error);
  const submit = useChatCommandStore((state) => state.submit);
  const reset = useChatCommandStore((state) => state.reset);

  return {
    lastResponse,
    history,
    isSubmitting,
    error,
    submit,
    reset,
  };
};
