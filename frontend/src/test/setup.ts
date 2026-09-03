import '@testing-library/jest-dom/vitest';

/**
 * jsdom 에는 `PointerEvent` 가 없다.
 *
 * 없으면 `fireEvent.pointerDown` 이 밋밋한 `Event` 를 만들고 `button` 과 `pointerId` 가
 * 사라진다. 그리드의 기간 선택은 왼쪽 버튼인지 보고 갈리므로, 폴리필이 없으면 테스트가
 * 조용히 아무것도 하지 않은 채 통과한다. `MouseEvent` 를 상속하면 좌표와 버튼이 그대로 산다.
 */
if (typeof window.PointerEvent === 'undefined') {
  class PointerEventPolyfill extends MouseEvent {
    readonly pointerId: number;
    readonly pointerType: string;
    readonly isPrimary: boolean;

    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 0;
      this.pointerType = init.pointerType ?? 'mouse';
      this.isPrimary = init.isPrimary ?? true;
    }
  }

  window.PointerEvent = PointerEventPolyfill as unknown as typeof PointerEvent;
}
