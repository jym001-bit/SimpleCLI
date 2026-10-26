export function clampRetryDelay(attemptDelayMs, retryAfterMs, maxWaitMs) {
  const requestedDelay = retryAfterMs == null
    ? attemptDelayMs
    : Math.max(attemptDelayMs, retryAfterMs);
  return Math.min(requestedDelay, maxWaitMs);
}
