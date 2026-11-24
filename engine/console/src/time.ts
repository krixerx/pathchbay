/**
 * A short, human date for tables: "today, 18:24", "yesterday, 09:05", or the date alone ("3 Nov 2026") further back.
 * The full timestamp goes in a tooltip or title next to it.
 */
export function shortWhen(at: Date, now: Date = new Date(), locale?: string): string {
  const day = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((day(now) - day(at)) / 86_400_000);
  const time = at.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
  if (days === 0) return `today, ${time}`;
  if (days === 1) return `yesterday, ${time}`;
  return at.toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' });
}
