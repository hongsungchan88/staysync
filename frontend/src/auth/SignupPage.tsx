import { useState } from 'react';
import { Link } from 'react-router-dom';
import { signup } from '@/api/auth';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/**
 * 회원가입 화면(작업지시-19 A). `/signup`.
 *
 * 서버 `POST /api/auth/signup` 이 받는 넷 그대로다 — 이메일, 비밀번호, 이름, 조직 이름.
 * 성공하면 토큰이 로그인과 같이 저장되어 라우터가 캘린더를 그린다. 이 화면이 이동을
 * 지시하지 않는 이유다 — 인증은 경로가 아니라 토큰 유무로 갈린다(ADR 0009).
 *
 * **비밀번호 규칙은 서버가 이미 적용하는 것(길이 10~100)만 보여 준다.** 화면에서 새 규칙을
 * 만들지 않는다 — 화면과 서버가 갈리면 서버가 이기고, 사용자는 이유를 모른 채 막힌다.
 * 이미 쓰는 이메일은 서버가 409 와 메시지를 주고, 그 메시지를 그대로 보여 준다.
 */
export function SignupPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [orgName, setOrgName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signup({ email, password, displayName, orgName });
    } catch (e) {
      setError(
        e instanceof ApiError
          ? e.message
          : '가입하지 못했습니다. 잠시 뒤 다시 시도해 주세요.',
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
        aria-label="가입"
      >
        <h1 className="text-lg font-semibold text-ink">StaySync 시작하기</h1>
        <p className="mt-1 text-sm text-muted">계정과 조직이 함께 만들어집니다.</p>

        <label className="mt-5 block text-xs text-muted" htmlFor="signup-email">
          이메일
        </label>
        <Input
          id="signup-email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />

        <label className="mt-3 block text-xs text-muted" htmlFor="signup-password">
          비밀번호 (10자 이상)
        </label>
        <Input
          id="signup-password"
          type="password"
          autoComplete="new-password"
          minLength={10}
          maxLength={100}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />

        <label className="mt-3 block text-xs text-muted" htmlFor="signup-name">
          이름
        </label>
        <Input
          id="signup-name"
          maxLength={100}
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          required
        />

        <label className="mt-3 block text-xs text-muted" htmlFor="signup-org">
          조직 이름 (숙소를 운영하는 이름)
        </label>
        <Input
          id="signup-org"
          maxLength={200}
          value={orgName}
          onChange={(e) => setOrgName(e.target.value)}
          required
        />

        {error && (
          <p role="alert" className="mt-3 text-xs text-warn">
            {error}
          </p>
        )}

        <Button type="submit" variant="primary" className="mt-5 w-full" disabled={busy}>
          {busy ? '만드는 중…' : '가입'}
        </Button>

        <p className="mt-4 text-center text-xs text-muted">
          이미 계정이 있으면{' '}
          <Link to="/" className="text-clay underline-offset-2 hover:underline">
            로그인
          </Link>
        </p>
      </form>
    </div>
  );
}
