import { useState } from 'react';
import { login } from '@/api/auth';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/**
 * 로그인 화면.
 *
 * 회원가입 화면은 만들지 않는다. 7주차에는 개발용 계정 하나면 되고, 가입 API 는
 * 이미 있으므로 필요하면 curl 로 만든다. 화면은 각자의 단계에 있다.
 */
export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
    } catch (e) {
      // 서버가 이메일 없음과 비밀번호 틀림을 구분하지 않는다(ADR 0005 계열 결정).
      // 화면도 구분해 보여주지 않는다. 여기서 추측해 안내하면 그 결정이 무너진다.
      setError(
        e instanceof ApiError && e.code === 'TOO_MANY_LOGIN_ATTEMPTS'
          ? '로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.'
          : '이메일 또는 비밀번호가 올바르지 않습니다.',
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-full items-center justify-center">
      <form
        onSubmit={submit}
        className="w-80 rounded-lg border border-rule bg-paper p-6"
        aria-label="로그인"
      >
        <h1 className="text-lg font-semibold text-ink">StaySync</h1>
        <p className="mt-1 text-sm text-muted">통합 캘린더에 들어가려면 로그인하세요.</p>

        <label className="mt-5 block text-xs text-muted" htmlFor="email">
          이메일
        </label>
        <Input
          id="email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />

        <label className="mt-3 block text-xs text-muted" htmlFor="password">
          비밀번호
        </label>
        <Input
          id="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />

        {error && (
          <p role="alert" className="mt-3 text-xs text-warn">
            {error}
          </p>
        )}

        <Button type="submit" variant="primary" className="mt-5 w-full" disabled={busy}>
          {busy ? '확인 중…' : '로그인'}
        </Button>
      </form>
    </div>
  );
}
