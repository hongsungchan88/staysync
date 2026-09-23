/**
 * 채널별 막대 색. 와이어프레임의 범례에서 가져왔다.
 *
 * 백엔드의 `channel_code` 를 키로 쓴다. 지금 실제로 생기는 값은 수기 예약의
 * `DIRECT` 뿐이고 나머지는 P3 에서 채널 어댑터가 붙으면서 들어온다. 미리 정의해 두면
 * 그때 화면을 손대지 않아도 된다.
 */
const CHANNEL_COLORS: Record<string, string> = {
  AIRBNB_ICAL: '#b25c42',
  BOOKING_COM: '#3f5a6b',
  NAVER: '#5c7166',
  DIRECT: '#71634a',
};

/** 알 수 없는 채널은 회색으로 그린다. 화면이 깨지는 것보다 낫다. */
const UNKNOWN_COLOR = '#8a8f96';

const CHANNEL_LABELS: Record<string, string> = {
  AIRBNB_ICAL: '에어비앤비',
  BOOKING_COM: '부킹닷컴',
  NAVER: '네이버',
  DIRECT: '직접예약',
};

export function channelColor(code: string): string {
  return CHANNEL_COLORS[code] ?? UNKNOWN_COLOR;
}

export function channelLabel(code: string): string {
  return CHANNEL_LABELS[code] ?? code;
}

/**
 * iCal 로 들어온 예약인가. iCal 연결의 채널 코드는 `AIRBNB_ICAL` 처럼 `_ICAL` 로 끝난다.
 * ponytail: 채널 코드 이름에 기댄다. 규칙을 벗어난 코드가 생기면 막대에 어댑터 종류를 싣는다.
 */
export function isIcalChannel(code: string): boolean {
  return code.endsWith('_ICAL');
}

/**
 * 막대에 적을 게스트 이름. iCal 은 발행물에 이름이 없다(계획서 15.3) — 이유 없이
 * "이름 없음"만 두면 고장으로 읽혀서 짧게 출처를 붙인다. 긴 설명은 예약 패널에 있다.
 */
export function guestLabel(bar: { guestName?: string | null; channel: string }): string {
  if (bar.guestName) {
    return bar.guestName;
  }
  return isIcalChannel(bar.channel) ? '이름 없음(iCal)' : '이름 없음';
}

/** 범례에 그릴 채널 목록. 순서는 와이어프레임과 같다. */
export const LEGEND = [
  { code: 'AIRBNB_ICAL', label: '에어비앤비' },
  { code: 'BOOKING_COM', label: '부킹닷컴' },
  { code: 'NAVER', label: '네이버' },
  { code: 'DIRECT', label: '직접예약' },
] as const;

/** 충돌 표시 색. 채널이 아니라 상태다. */
export const CONFLICT_COLOR = '#b4472c';
