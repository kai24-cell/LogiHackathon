import {
  CONNECTION_TOKEN_PATTERN,
  MAX_LOCAL_PORT,
  MIN_LOCAL_PORT,
  PROTOCOL_VERSION,
} from "./config";

export interface Runtime {
  port: number;
  token: string;
  protocolVersion: string;
}
export function validateRuntime(input: unknown): Runtime {
  if (typeof input !== "object" || input === null)
    throw new Error("INVALID_RUNTIME");
  const data = input as Partial<Runtime>;
  if (
    !Number.isInteger(data.port) ||
    data.port! < MIN_LOCAL_PORT ||
    data.port! > MAX_LOCAL_PORT ||
    typeof data.token !== "string" ||
    !CONNECTION_TOKEN_PATTERN.test(data.token) ||
    data.protocolVersion !== PROTOCOL_VERSION
  )
    throw new Error("INVALID_RUNTIME");
  return data as Runtime;
}
