import { useState } from 'react';
import { Link } from 'react-router-dom';
import { changePassword } from '@/api/auth';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/** 가입과 같은 규칙(서버 `AuthDtos.PASSWORD_MIN`·`PASSWORD_MAX`). 화면에서 새 규칙을 만들지 않는다. */
const MIN = 10;
const MAX = 100;

type Field = 'current' | 'next' | 'confirm';

/**
 * 비밀번호 변경(작업지시-23 D). `/account/password`.
 *
 * 틀린 칸을 알려 준다 — 현재 비밀번호가 틀렸는지, 새 비밀번호가 규칙에 안 맞는지, 확인이 다른지. 확인 입력은
 * 화면만 맞춰 보고 서버는 새 값 하나만 받는다.
 *
 * 바꾸면 이 로그인은 그대로이고 같은 계정의 다른 로그인은 끊긴다. 공용 계정이면 다른 사람이 다시 로그인해야
 * 하므로 그 사실을 미리 적어 둔다.
 */
export function PasswordPage() {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<{ field: Field | null; message: string } | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setDone(false);
    if (next.length < MIN || next.length > MAX) {
      setError({ field: 'next', message: `새 비밀번호는 ${MIN}자 이상 ${MAX}자 이하여야 합니다.` });
      return;
    }
    if (next !== confirm) {
      setError({ field: 'confirm', message: '새 비밀번호 확인이 다릅니다.' });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await changePassword(current, next);
      setDone(true);
      setCurrent('');
      setNext('');
      setConfirm('');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'CURRENT_PASSWORD_MISMATCH') {
        setError({ field: 'current', message: e.message });
      } else if (e instanceof ApiError && e.code === 'VALIDATION_FAILED') {
        setError({ field: 'next', message: `새 비밀번호는 ${MIN}자 이상 ${MAX}자 이하여야 합니다.` });
      } else {
        setError({
          field: null,
          message: e instanceof ApiError ? e.message : '바꾸지 못했습니다. 잠시 뒤 다시 시도해 주세요.',
        });
      }
    } finally {
      setBusy(false);
    }
  }

  const invalid = (field: Field) => (error?.field === field ? true : undefined);

  return (
    <div className="flex h-full flex-col">
      <header className="border-b border-rule bg-paper px-6 py-3">
        <Link to="/" className="text-sm text-clay underline-offset-2 hover:underline">
          캘린더로
        </Link>
      </header>
      <div className="flex flex-1 items-center justify-center">
        <form
          onSubmit={submit}
          className="w-80 rounded-lg border border-rule bg-paper p-6"
          aria-label="비밀번호 변경"
          noValidate
        >
          <h1 className="text-lg font-semibold text-ink">비밀번호 변경</h1>
          <p className="mt-1 text-xs leading-relaxed text-muted">
            바꾸면 이 계정으로 로그인해 있는 다른 기기·사람은 다시 로그인해야 합니다. 이 화면은 그대로입니다.
          </p>

          <label className="mt-5 block text-xs text-muted" htmlFor="pw-current">
            현재 비밀번호
          </label>
          <Input
            id="pw-current"
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            aria-invalid={invalid('current')}
            required
          />

          <label className="mt-3 block text-xs text-muted" htmlFor="pw-next">
            새 비밀번호 ({MIN}자 이상)
          </label>
          <Input
            id="pw-next"
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            aria-invalid={invalid('next')}
            required
          />

          <label className="mt-3 block text-xs text-muted" htmlFor="pw-confirm">
            새 비밀번호 확인
          </label>
          <Input
            id="pw-confirm"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            aria-invalid={invalid('confirm')}
            required
          />

          {error && (
            <p role="alert" className="mt-3 text-xs text-warn" data-field={error.field ?? undefined}>
              {error.message}
            </p>
          )}
          {done && (
            <p role="status" className="mt-3 text-xs text-body">
              비밀번호를 바꿨습니다.
            </p>
          )}

          <Button
            type="submit"
            variant="primary"
            className="mt-5 w-full"
            disabled={busy || current === '' || next === '' || confirm === ''}
          >
            {busy ? '바꾸는 중…' : '바꾸기'}
          </Button>
        </form>
      </div>
    </div>
  );
}
