import { retryConfig } from '../config/runtime.js';
import { clampRetryDelay } from './retry-window.js';

export function nextRetryDelay(attempt, retryAfterMs) {
  const exponentialDelay = retryConfig.baseDelayMs * (2 ** attempt);
  return clampRetryDelay(exponentialDelay, retryAfterMs, retryConfig.maxWaitMs);
}
