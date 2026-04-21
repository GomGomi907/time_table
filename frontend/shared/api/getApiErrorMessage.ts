import axios from "axios";
import { ApiRequestError, isApiEnvelope } from "./client";

interface ApiErrorShape {
  message?: string;
  error?: {
    message?: string;
  };
}

export const getApiErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof ApiRequestError && error.message.trim()) {
    return error.message.trim();
  }

  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiErrorShape | undefined;

    if (isApiEnvelope(data) && data.error?.message?.trim()) {
      return data.error.message.trim();
    }

    if (typeof data?.error?.message === "string" && data.error.message.trim()) {
      return data.error.message.trim();
    }

    if (typeof data?.message === "string" && data.message.trim()) {
      return data.message.trim();
    }
  }

  if (error instanceof Error && error.message.trim()) {
    return error.message.trim();
  }

  return fallback;
};
