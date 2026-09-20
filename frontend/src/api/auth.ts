import { apiRequest } from './client';
import { meResponseSchema, tokenResponseSchema, type MeResponse, type TokenResponse } from './schemas';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 로그인.
 *
 * `skipAuthRetry` 를 켠다. 로그인 실패의 401 은 만료가 아니라 자격증명이 틀린 것이라
 * 갱신을 시도할 이유가 없다. 켜지 않으면 비밀번호를 틀릴 때마다 쓸모없는 갱신 요청이
 * 함께 나간다.
 */
export async function login(email: string, password: string): Promise<TokenResponse> {
  const body = await apiRequest<unknown>('/api/auth/login', {
    method: 'POST',
    body: { email, password },
    skipAuthRetry: true,
  });
  const parsed = tokenResponseSchema.parse(body);
  tokenStore.set(parsed.accessToken);
  return parsed;
}

/**
 * 가입. 서버가 조직 하나와 OWNER 한 명을 함께 만들고 로그인과 같은 토큰 한 쌍을 준다
 * (작업지시-19 A). 응답 모양이 로그인과 같아 그대로 세션이 된다.
 */
export async function signup(input: {
  email: string;
  password: string;
  displayName: string;
  orgName: string;
}): Promise<TokenResponse> {
  const body = await apiRequest<unknown>('/api/auth/signup', {
    method: 'POST',
    body: input,
    skipAuthRetry: true,
  });
  const parsed = tokenResponseSchema.parse(body);
  tokenStore.set(parsed.accessToken);
  return parsed;
}

export async function logout(): Promise<void> {
  try {
    await apiRequest<void>('/api/auth/logout', { method: 'POST' });
  } finally {
    // 서버 호출이 실패해도 화면에서는 로그아웃된 것으로 다룬다. 남겨 두면
    // 사용자는 로그아웃했다고 믿는데 토큰이 살아 있다.
    tokenStore.clear();
  }
}

export async function fetchMe(): Promise<MeResponse> {
  return meResponseSchema.parse(await apiRequest<unknown>('/api/auth/me'));
}
