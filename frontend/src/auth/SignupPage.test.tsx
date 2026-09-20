import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SignupPage } from './SignupPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 작업지시-19 완료 조건 10 의 가입 화면. 서버가 실제로 보내는 응답 모양 그대로 —
 * 성공은 로그인과 같은 `TokenResponse`, 중복 이메일은 `ApiError` 409 다.
 */

let fetchMock: ReturnType<typeof vi.fn>;

function json(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

beforeEach(() => {
  tokenStore.clear();
  resetRefreshState();
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
});

async function 넷을_채운다() {
  await userEvent.type(screen.getByLabelText('이메일'), 'host@example.com');
  await userEvent.type(screen.getByLabelText(/비밀번호/), '충분히긴비밀번호1234');
  await userEvent.type(screen.getByLabelText('이름'), '호스트');
  await userEvent.type(screen.getByLabelText(/조직 이름/), '새 조직');
}

describe('가입 화면', () => {
  it('넷을 보내고 성공하면 토큰이 저장되어 로그인 상태가 된다', async () => {
    fetchMock.mockResolvedValue(
      json({ accessToken: 'jwt-after-signup', tokenType: 'Bearer', expiresInSeconds: 1800 }, 201),
    );
    render(<MemoryRouter><SignupPage /></MemoryRouter>);

    await 넷을_채운다();
    await userEvent.click(screen.getByRole('button', { name: '가입' }));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/auth/signup');
    expect(JSON.parse(init.body as string)).toEqual({
      email: 'host@example.com',
      password: '충분히긴비밀번호1234',
      displayName: '호스트',
      orgName: '새 조직',
    });
    // 이동을 지시하지 않는다 — 토큰이 생기면 라우터가 캘린더를 그린다.
    expect(tokenStore.get()).toBe('jwt-after-signup');
  });

  it('이미 쓰는 이메일이면 서버의 409 메시지를 그대로 보여 준다', async () => {
    fetchMock.mockResolvedValue(
      json(
        {
          code: 'EMAIL_ALREADY_USED',
          message: '이미 사용 중인 이메일입니다. email=host@example.com',
          details: [],
        },
        409,
      ),
    );
    render(<MemoryRouter><SignupPage /></MemoryRouter>);

    await 넷을_채운다();
    await userEvent.click(screen.getByRole('button', { name: '가입' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 사용 중인 이메일입니다');
    expect(tokenStore.get()).toBeNull();
    // 로그인으로 돌아가는 길이 있다.
    expect(screen.getByRole('link', { name: '로그인' })).toBeInTheDocument();
  });
});
