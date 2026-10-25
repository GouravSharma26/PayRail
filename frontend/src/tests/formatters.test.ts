import { describe, it, expect, vi } from 'vitest';
import {
  formatCurrency,
  formatDurationSeconds,
  formatTimestamp,
  truncateUuid,
} from '../utils/formatters';

describe('formatters', () => {
  describe('formatCurrency', () => {
    it('formats decimal amounts as exact decimal strings with grouping without parseFloat', () => {
      // Spy on parseFloat to guarantee monetary formatting never invokes binary floating-point conversion
      const parseFloatSpy = vi.spyOn(window, 'parseFloat');

      const formatted = formatCurrency('1250000.50', 'USD');
      expect(formatted).toBe('1,250,000.50 USD');

      expect(parseFloatSpy).not.toHaveBeenCalled();
      parseFloatSpy.mockRestore();
    });

    it('preserves exact scale and precision for large decimal values', () => {
      expect(formatCurrency('999999999999.99', 'EUR')).toBe('999,999,999,999.99 EUR');
      expect(formatCurrency('100.00', 'GBP')).toBe('100.00 GBP');
      expect(formatCurrency('0.05', 'USD')).toBe('0.05 USD');
      expect(formatCurrency('0.00', 'USD')).toBe('0.00 USD');
    });

    it('formats negative amounts correctly without floating-point math', () => {
      expect(formatCurrency('-450.25', 'USD')).toBe('-450.25 USD');
      expect(formatCurrency('-1000000.00', 'EUR')).toBe('-1,000,000.00 EUR');
    });

    it('handles undefined or null amounts safely with placeholder', () => {
      expect(formatCurrency(undefined)).toBe('—');
      expect(formatCurrency(null)).toBe('—');
    });
  });

  describe('formatTimestamp', () => {
    it('formats ISO timestamps to readable UTC strings', () => {
      const iso = '2026-10-25T12:30:00Z';
      const formatted = formatTimestamp(iso);
      expect(formatted).toContain('2026');
      expect(formatted).toContain('UTC');
    });

    it('handles null or empty timestamps gracefully', () => {
      expect(formatTimestamp(null)).toBe('—');
      expect(formatTimestamp(undefined)).toBe('—');
    });
  });

  describe('formatDurationSeconds', () => {
    it('formats seconds, minutes, and hours into readable durations', () => {
      expect(formatDurationSeconds(45)).toBe('45s');
      expect(formatDurationSeconds(125)).toBe('2m 5s');
      expect(formatDurationSeconds(3665)).toBe('1h 1m');
    });

    it('returns 0s for zero duration and dash for null', () => {
      expect(formatDurationSeconds(0)).toBe('0s');
      expect(formatDurationSeconds(null)).toBe('—');
      expect(formatDurationSeconds(undefined)).toBe('—');
    });
  });

  describe('truncateUuid', () => {
    it('truncates UUIDs with leading and trailing characters', () => {
      const uuid = 'c4a16dc3-5f78-43e5-829d-03487c32bf11';
      expect(truncateUuid(uuid, 6)).toBe('c4a16d…bf11');
    });

    it('returns original string if length is short or falsy', () => {
      expect(truncateUuid('short')).toBe('short');
      expect(truncateUuid('')).toBe('—');
    });
  });
});
