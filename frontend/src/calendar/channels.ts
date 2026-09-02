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

/** 범례에 그릴 채널 목록. 순서는 와이어프레임과 같다. */
export const LEGEND = [
  { code: 'AIRBNB_ICAL', label: '에어비앤비' },
  { code: 'BOOKING_COM', label: '부킹닷컴' },
  { code: 'NAVER', label: '네이버' },
  { code: 'DIRECT', label: '직접예약' },
] as const;

/** 충돌 표시 색. 채널이 아니라 상태다. */
export const CONFLICT_COLOR = '#b4472c';
