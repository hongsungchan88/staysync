import type { AdapterType } from '@/api/schemas';

/**
 * 어댑터가 지원하는 기능을 화면에 어떻게 보여줄지.
 *
 * **이 화면의 요점이 여기 있다.** 같은 "채널 연결"이라도 iCal 은 요금을 보낼 수 없다.
 * 그걸 호스트가 요금을 바꾼 뒤가 아니라 연결 목록에서 미리 알아야 한다.
 * 계획서 6.1 이 말하는 설계의 요점이 드러나는 자리다.
 *
 * 지원 여부의 출처는 서버다(`AdapterType.capabilities()`). 화면은 이름만 붙인다.
 */
const CAPABILITY_LABELS: Record<string, string> = {
  PUSH_AVAILABILITY: '재고 전파',
  PUSH_RATE: '요금 전파',
  PUSH_RESTRICTION: '제약 전파',
  PULL_BOOKING: '예약 수집',
  WEBHOOK_BOOKING: '예약 웹훅',
  MESSAGING: '메시지',
  REVIEW: '리뷰',
  CONTENT: '콘텐츠',
};

/**
 * 목록에서 지원 여부를 표시할 기능. 동기화에 직접 관계된 것만 고른다.
 *
 * 여덟 가지를 전부 늘어놓으면 정작 봐야 할 "요금을 보낼 수 있나"가 묻힌다.
 * 메시지·리뷰·콘텐츠는 아직 어느 어댑터도 지원하지 않는다.
 */
export const SHOWN_CAPABILITIES = [
  'PUSH_AVAILABILITY',
  'PUSH_RATE',
  'PUSH_RESTRICTION',
  'PULL_BOOKING',
] as const;

export function capabilityLabel(code: string): string {
  return CAPABILITY_LABELS[code] ?? code;
}

/**
 * 어댑터 종류가 요구하는 자격 증명의 키.
 *
 * 서버는 키를 강제하지 않고 받은 대로 저장한다. 해석은 어댑터의 몫이라서다.
 * 화면은 호스트에게 무엇을 입력하라고 물어야 하므로 여기서 정한다.
 */
export function credentialKey(adapterType: AdapterType): string {
  return adapterType === 'ICAL' ? 'ical_url' : 'api_key';
}

export function credentialLabel(adapterType: AdapterType): string {
  return adapterType === 'ICAL' ? 'iCal 내보내기 주소' : 'API 키';
}

/**
 * 채널 쪽 식별자를 무엇으로 입력해야 하는지.
 *
 * 채널마다 모양이 다르다 — iCal 은 리스팅 하나, Channex 는 room_type_id 다.
 * 매핑 테이블이 그 차이를 흡수하므로 서버는 문자열로만 다루고, 화면이 안내한다.
 */
export function externalIdHint(adapterType: AdapterType): string {
  switch (adapterType) {
    case 'ICAL':
      return '리스팅 식별자';
    case 'CHANNEX':
      return 'room_type_id';
    default:
      return '시뮬레이터가 정한 식별자';
  }
}
