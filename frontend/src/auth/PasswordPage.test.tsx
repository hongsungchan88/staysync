import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PasswordPage } from './PasswordPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 비밀번호 변경 화면(작업지시-23 D). 서버가 실제로 보내는 모양 그대로 — 성공 204, 현재 비밀번호가 틀리면
 * 400 `CURRENT_PASSWORD_MISMATCH`(401 이 아니다).
 */

let fetchMock: ReturnType<typeof vi.fn>;

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

beforeEach(() => {
  resetRefreshState();
  tokenStore.set('테스트-토큰');
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
});

async function 채운다(현재: string, 새것: string, 확인: string) {
  render(<MemoryRouter><PasswordPage /></MemoryRouter>);
  await userEvent.type(screen.getByLabelText('현재 비밀번호'), 현재);
  await userEvent.type(screen.getByLabelText(/^새 비밀번호 \(/), 새것);
  await userEvent.type(screen.getByLabelText('새 비밀번호 확인'), 확인);
  await userEvent.click(screen.getByRole('button', { name: '바꾸기' }));
}

describe('비밀번호 변경 화면', () => {
  it('현재·새 비밀번호를 보내고 성공을 알린다. 로그인은 그대로다', async () => {
    fetchMock.mockResolvedValue(response(204));
    await 채운다('지금비밀번호1234', '새로운비밀번호5678', '새로운비밀번호5678');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/auth/password');
    // 확인 입력은 화면만 본다. 서버에는 새 값 하나만 간다.
    expect(JSON.parse(init.body as string)).toEqual({
      currentPassword: '지금비밀번호1234',
      newPassword: '새로운비밀번호5678',
    });
    expect(await screen.findByRole('status')).toHaveTextContent('바꿨습니다');
    expect(tokenStore.get()).toBe('테스트-토큰');
  });

  it('확인이 다르면 보내지 않고 확인 칸을 짚는다', async () => {
    await 채운다('지금비밀번호1234', '새로운비밀번호5678', '새로운비밀번호0000');

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('확인이 다릅니다');
    expect(screen.getByLabelText('새 비밀번호 확인')).toHaveAttribute('aria-invalid', 'true');
  });

  it('새 비밀번호가 가입 규칙(10자 이상)에 안 맞으면 보내지 않는다', async () => {
    await 채운다('지금비밀번호1234', '짧은비번', '짧은비번');

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('10자 이상');
    expect(screen.getByLabelText(/^새 비밀번호 \(/)).toHaveAttribute('aria-invalid', 'true');
  });

  it('현재 비밀번호가 틀리면 그 칸을 짚고 로그인은 그대로다', async () => {
    fetchMock.mockResolvedValue(
      response(400, {
        code: 'CURRENT_PASSWORD_MISMATCH',
        message: '현재 비밀번호가 맞지 않습니다.',
        details: [],
      }),
    );
    await 채운다('틀린비밀번호0000', '새로운비밀번호5678', '새로운비밀번호5678');

    expect(await screen.findByRole('alert')).toHaveTextContent('현재 비밀번호가 맞지 않습니다');
    expect(screen.getByLabelText('현재 비밀번호')).toHaveAttribute('aria-invalid', 'true');
    // 400 이라 갱신도 로그아웃도 일어나지 않는다. 요청은 하나뿐이다.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(tokenStore.get()).toBe('테스트-토큰');
  });
});
