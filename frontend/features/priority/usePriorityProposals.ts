"use client";

import { useEffect } from "react";
import { usePriorityProposalsStore } from "./storePriorityProposals";

export const usePriorityProposals = () => {
  const data = usePriorityProposalsStore((state) => state.items);
  const isLoading = usePriorityProposalsStore((state) => state.isLoading);
  const isMutating = usePriorityProposalsStore((state) => state.isMutating);
  const hasLoaded = usePriorityProposalsStore((state) => state.hasLoaded);
  const error = usePriorityProposalsStore((state) => state.error);
  const refresh = usePriorityProposalsStore((state) => state.refresh);
  const acceptProposal = usePriorityProposalsStore((state) => state.acceptProposal);
  const rejectProposal = usePriorityProposalsStore((state) => state.rejectProposal);

  useEffect(() => {
    if (!hasLoaded) {
      void refresh();
    }
  }, [hasLoaded, refresh]);

  return {
    data,
    isLoading,
    isMutating,
    error,
    refresh,
    acceptProposal,
    rejectProposal,
  };
};
