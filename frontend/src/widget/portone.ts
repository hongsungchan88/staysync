/**
 * 포트원 V2 결제창 SDK 로더.
 *
 * **필요해질 때 불러온다.** 위젯을 열기만 한 손님에게 남의 스크립트를 미리 내려받게
 * 할 이유가 없다. 결제 버튼을 누르는 순간이 그 시점이다.
 *
 * **결제창의 성공을 확정으로 쓰지 않는다.** 여기서 받는 것은 브라우저가 본 결과이고,
 * 우리 예약은 포트원이 서버로 보내는 웹훅이 도착해야 확정된다. 그래서 이 모듈은
 * "결제창이 실패로 끝났는지"만 판정하고, 성공 여부는 서버에 다시 물어본다.
 */

const SDK_URL = 'https://cdn.portone.io/v2/browser-sdk.js';

/** 결제창이 돌려주는 것. 실패했을 때만 `code` 가 있다. */
export interface PayResponse {
  code?: string;
  message?: string;
}

export interface PayRequest {
  storeId: string;
  channelKey: string;
  paymentId: string;
  orderName: string;
  totalAmount: number;
  currency: string;
  payMethod: string;
}

interface PortOneSdk {
  requestPayment(request: PayRequest): Promise<PayResponse | undefined>;
}

declare global {
  interface Window {
    PortOne?: PortOneSdk;
  }
}

/** 같은 스크립트를 두 번 넣지 않는다. 두 번 넣으면 전역이 두 번 덮인다. */
let loading: Promise<PortOneSdk> | null = null;

export function loadPortOne(): Promise<PortOneSdk> {
  if (window.PortOne) {
    return Promise.resolve(window.PortOne);
  }
  if (loading) {
    return loading;
  }
  loading = new Promise<PortOneSdk>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = SDK_URL;
    script.onload = () => {
      if (window.PortOne) {
        resolve(window.PortOne);
      } else {
        reject(new Error('결제 모듈을 불러오지 못했습니다.'));
      }
    };
    script.onerror = () => {
      // 다음 시도에서 다시 넣을 수 있어야 한다. 비우지 않으면 한 번 실패한 뒤
      // 새로고침 전까지 영영 결제를 못 한다.
      loading = null;
      reject(new Error('결제 모듈을 불러오지 못했습니다.'));
    };
    document.head.appendChild(script);
  });
  return loading;
}
