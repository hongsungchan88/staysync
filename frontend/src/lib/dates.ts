/** 날짜 유틸. 화면은 전부 `YYYY-MM-DD` 문자열로 다룬다. */

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'] as const;

export function toIso(date: Date): string {
  // toISOString 은 UTC 로 바꾸므로 한국 시간대에서 하루가 밀린다. 직접 만든다.
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function parseIso(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1);
}

export function addDays(iso: string, days: number): string {
  const date = parseIso(iso);
  date.setDate(date.getDate() + days);
  return toIso(date);
}

/** 두 날짜 사이의 일수. 양끝을 포함하지 않는 차이다. */
export function daysBetween(fromIso: string, toIso_: string): number {
  return Math.round((parseIso(toIso_).getTime() - parseIso(fromIso).getTime()) / 86_400_000);
}

export function dayOfMonth(iso: string): number {
  return parseIso(iso).getDate();
}

export function weekdayLabel(iso: string): string {
  return WEEKDAYS[parseIso(iso).getDay()] ?? '';
}

export function isWeekend(iso: string): boolean {
  const day = parseIso(iso).getDay();
  return day === 0 || day === 6;
}

export function monthLabel(iso: string): string {
  const date = parseIso(iso);
  return `${date.getFullYear()}년 ${date.getMonth() + 1}월`;
}
