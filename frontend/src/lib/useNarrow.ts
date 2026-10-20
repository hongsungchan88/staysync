import { useEffect, useState } from 'react';

/**
 * 휴대폰 폭인지(작업지시-29). Tailwind `md`(768px) 아래다 — CSS 의 `max-md:` 과 같은 경계여야 한쪽만 바뀌지 않는다.
 *
 * CSS 만으로 안 되는 곳이 쓴다: 그리드 왼쪽 칸 폭(가상 스크롤·좌표 계산이 자바스크립트 값을 쓴다)과 터치 넘기기.
 * `matchMedia` 가 없는 환경(테스트)에서는 넓은 화면이다.
 */
const QUERY = '(max-width: 767px)';

export function useNarrow(): boolean {
  const [narrow, setNarrow] = useState(() => typeof window.matchMedia === 'function' && window.matchMedia(QUERY).matches);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const media = window.matchMedia(QUERY);
    const update = () => setNarrow(media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  return narrow;
}
