/**
 * Monetary formatting utility strictly preserving decimal string precision.
 * NEVER uses parseFloat or binary floating-point conversions.
 */
export function formatCurrency(amount: string | null | undefined, currency?: string): string {
  if (amount === null || amount === undefined || amount === '') {
    return '—';
  }

  const trimmed = amount.trim();
  const isNegative = trimmed.startsWith('-');
  const rawAbs = isNegative ? trimmed.slice(1) : trimmed;

  const parts = rawAbs.split('.');
  const integerPart = parts[0] || '0';
  const fractionalPart = parts.length > 1 ? parts[1] : '00';

  // Format integer portion with standard commas
  const formattedInteger = integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const sign = isNegative ? '-' : '';
  const formattedNumber = `${sign}${formattedInteger}.${fractionalPart}`;

  if (currency) {
    return `${formattedNumber} ${currency}`;
  }
  return formattedNumber;
}

/**
 * Format ISO-8601 UTC timestamp to readable display.
 */
export function formatTimestamp(isoString: string | null | undefined): string {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return d.toISOString().replace('T', ' ').replace('Z', ' UTC');
  } catch {
    return isoString;
  }
}

/**
 * Friendly relative time or duration format.
 */
export function formatDurationSeconds(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—';
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainingSecs = seconds % 60;
  if (minutes < 60) return `${minutes}m ${remainingSecs}s`;
  const hours = Math.floor(minutes / 60);
  const remainingMins = minutes % 60;
  return `${hours}h ${remainingMins}m`;
}

/**
 * Truncate long UUIDs for readable UI display while preserving title tooltip.
 */
export function truncateUuid(id: string | null | undefined, prefixLen = 8, suffixLen = 4): string {
  if (!id) return '—';
  if (id.length <= prefixLen + suffixLen + 2) return id;
  return `${id.slice(0, prefixLen)}…${id.slice(-suffixLen)}`;
}
