import axios, { type AxiosResponse } from "axios";
import type { ApiEnvelope, ApiError, ApiMeta, ApiResult } from "./types";

const createClient = () =>
  axios.create({
    baseURL: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api",
    withCredentials: true,
    xsrfCookieName: "XSRF-TOKEN",
    xsrfHeaderName: "X-XSRF-TOKEN",
    headers: {
      "Content-Type": "application/json",
    },
  });

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

export const normalizeApiList = <T>(value: unknown): T[] => {
  if (Array.isArray(value)) {
    return value as T[];
  }

  if (isRecord(value)) {
    if (Array.isArray(value.data)) {
      return value.data as T[];
    }

    if (Array.isArray(value.items)) {
      return value.items as T[];
    }
  }

  return [];
};

export class ApiRequestError extends Error {
  readonly code: string | null;
  readonly status: number | null;
  readonly details: unknown;
  readonly meta: ApiMeta;

  constructor({
    message,
    code,
    status,
    details,
    meta,
  }: {
    message: string;
    code?: string | null;
    status?: number | null;
    details?: unknown;
    meta?: ApiMeta;
  }) {
    super(message);
    this.name = "ApiRequestError";
    this.code = code ?? null;
    this.status = status ?? null;
    this.details = details;
    this.meta = meta ?? {};
  }
}

export const isApiEnvelope = <TData, TMeta extends ApiMeta = ApiMeta>(
  value: unknown
): value is ApiEnvelope<TData, TMeta> =>
  isRecord(value) &&
  "success" in value &&
  "data" in value &&
  "meta" in value &&
  "error" in value;

const toApiError = (value: unknown): ApiError => {
  if (!isRecord(value)) {
    return {
      code: "UNKNOWN_ERROR",
      message: "알 수 없는 API 오류가 발생했습니다.",
    };
  }

  return {
    code: typeof value.code === "string" && value.code.trim() ? value.code : "UNKNOWN_ERROR",
    message:
      typeof value.message === "string" && value.message.trim()
        ? value.message
        : "알 수 없는 API 오류가 발생했습니다.",
    details: "details" in value ? value.details : value,
  };
};

const toApiRequestError = (
  value: unknown,
  status?: number | null,
  meta?: ApiMeta
): ApiRequestError => {
  const error = toApiError(value);
  return new ApiRequestError({
    message: error.message,
    code: error.code,
    status,
    details: error.details,
    meta,
  });
};

export const ensureApiEnvelope = <TData, TMeta extends ApiMeta = ApiMeta>(
  value: ApiResult<TData, TMeta>
): ApiEnvelope<TData, TMeta> => {
  if (isApiEnvelope<TData, TMeta>(value)) {
    return value;
  }

  return {
    success: true,
    data: value,
    meta: {} as TMeta,
    error: null,
  };
};

export const requestApiEnvelope = async <TData, TMeta extends ApiMeta = ApiMeta>(
  request: Promise<AxiosResponse<ApiResult<TData, TMeta>>>
): Promise<ApiEnvelope<TData, TMeta>> => {
  const response = await request;
  const envelope = ensureApiEnvelope<TData, TMeta>(response.data);

  if (!envelope.success) {
    throw toApiRequestError(envelope.error, response.status, envelope.meta);
  }

  return envelope;
};

export const requestApiData = async <TData, TMeta extends ApiMeta = ApiMeta>(
  request: Promise<AxiosResponse<ApiResult<TData, TMeta>>>
): Promise<TData> => {
  const envelope = await requestApiEnvelope<TData, TMeta>(request);
  return envelope.data as TData;
};

export const apiEnvelopeClient = createClient();
export const apiClient = createClient();

apiClient.interceptors.response.use(
  (response) => {
    const envelope = ensureApiEnvelope(response.data);

    if (!envelope.success) {
      return Promise.reject(toApiRequestError(envelope.error, response.status, envelope.meta));
    }

    return {
      ...response,
      data: envelope.data,
    };
  },
  (error) => {
    if (axios.isAxiosError(error)) {
      const payload = error.response?.data;

      if (isApiEnvelope(payload) && !payload.success) {
        return Promise.reject(
          toApiRequestError(payload.error, error.response?.status ?? null, payload.meta)
        );
      }
    }

    return Promise.reject(error);
  }
);
